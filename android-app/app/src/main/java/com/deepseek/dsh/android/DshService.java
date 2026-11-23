package com.deepseek.dsh.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DshService extends Service {

    private static final String TAG = "DshService";
    private static final String TAG_NODE = "DshNode";
    private static final String TAG_SHELL = "DshShell";
    private static final String CHANNEL_ID = "dsh";
    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static final int UPDATE_NOTIFICATION_ID = 2;

    private static final String NODE_BINARY_NAME = "libnodeexec.so";
    private static final String RUNTIME_ASSET = "runtime/dsh-runtime.zip";
    private static final String LIBS_ASSET_ROOT = "libs.zip";
    private static final String DSH_HOME_ASSET = "dsh-home.zip";
    private static final String SHELL_ASSET = "shell.zip";
    private static final String WEB_URL_PREFIX = "dsh web: ";
    private static final long RESTART_DELAY_MS = 3000;
    private static final int MAX_AUTO_RESTARTS = 5;

    private static final String CA_BUNDLE_NAME = "cacert.pem";
    private static final String SYSTEM_CA_DIR = "/system/etc/security/cacerts";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean workerBusy = new AtomicBoolean(false);
    private final AtomicBoolean serviceStopping = new AtomicBoolean(false);
    private int autoRestartCount = 0;
    private Process nodeProcess;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "DSH runtime", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("DSH node runtime foreground service");
            nm.createNotificationChannel(channel);
        }
        Log.i(TAG, "onCreate: channel 'dsh' ready (low importance)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification(
                "DSH 运行时启动中", "正在准备 runtime 并拉起 node…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        }
        serviceStopping.set(false);
        if (workerBusy.compareAndSet(false, true)) {
            executor.execute(this::runNodeLoop);
        } else {
            Log.i(TAG, "worker already busy; skip duplicate start");
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "onTaskRemoved: task swiped away; foreground service keeps node alive");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        serviceStopping.set(true);
        mainHandler.removeCallbacksAndMessages(null);
        Process p = nodeProcess;
        if (p != null) {
            p.destroy();
            Log.i(TAG, "onDestroy: node process killed");
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private void runNodeLoop() {
        try {
            String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "x86_64";
            File runtimeRoot = new File(getFilesDir(), "runtime");
            File nodeModules = new File(runtimeRoot, "dsh/node_modules");

            if (!nodeModules.exists()
                    || !new File(runtimeRoot, "dsh/pnpm/pnpm.js").exists()) {
                try {
                    unzipAsset(RUNTIME_ASSET, runtimeRoot);
                } catch (IOException e) {
                    Log.e(TAG, "runtime extraction failed", e);
                    notifyState("runtime 解压失败", String.valueOf(e.getMessage()));
                    workerBusy.set(false);
                    return;
                }
            } else {
                Log.i(TAG, "runtime already extracted at " + nodeModules);
            }

            File libsRoot = new File(getFilesDir(), "libs");
            File abiLibs = new File(libsRoot, abi);
            if (!new File(abiLibs, "libcrypto.so.3").exists()) {
                try {
                    unzipAsset(LIBS_ASSET_ROOT, libsRoot);
                } catch (IOException e) {
                    Log.e(TAG, "libs extraction failed", e);
                    notifyState("依赖库解压失败", String.valueOf(e.getMessage()));
                    workerBusy.set(false);
                    return;
                }
            } else {
                Log.i(TAG, "native libs already extracted at " + abiLibs);
            }

            File dshHomeDir = new File(getFilesDir(), "dsh-home");
            if (!new File(dshHomeDir, "profiles/android/cordis.patch.yml").exists()) {
                try {
                    unzipAsset(DSH_HOME_ASSET, dshHomeDir);
                } catch (IOException e) {
                    Log.e(TAG, "dsh-home extraction failed", e);
                    notifyState("dsh-home 解压失败", String.valueOf(e.getMessage()));
                    workerBusy.set(false);
                    return;
                }
            } else {
                Log.i(TAG, "dsh-home already extracted at " + dshHomeDir);
            }

            String nativeDir = getApplicationInfo().nativeLibraryDir;
            File binDir = new File(getFilesDir(), "bin");
            try {
                int linked = prepareShellTools(abi, nativeDir, binDir);
                Log.i(TAG_SHELL, "shell tools linked: " + linked);
            } catch (Exception e) {
                Log.e(TAG_SHELL, "shell tool preparation failed", e);
            }
            ensureDir(new File(getFilesDir(), "tmp"));

            runShellSelfTest(binDir, nativeDir, abiLibs.getAbsolutePath());
            File nodeBin = new File(nativeDir, NODE_BINARY_NAME);
            if (!nodeBin.exists()) {
                Log.w(TAG, "node binary not ready: " + nodeBin.getAbsolutePath());
                notifyState("node 二进制未就绪", "jniLibs 缺少 " + NODE_BINARY_NAME + "，等待集成");
                workerBusy.set(false);
                return;
            }

            File binJs = new File(nodeModules, "@deepseek-ai/dsh/lib/bin.js");

            List<String> cmd = new ArrayList<>();
            cmd.add(nodeBin.getAbsolutePath());
            cmd.add("--expose-internals");
            cmd.add(binJs.getAbsolutePath());
            cmd.add("--profile");
            cmd.add("android");
            cmd.add("--no-open");
            cmd.add("--port");
            cmd.add("3080");

            String filesDir = getFilesDir().getAbsolutePath();
            ensureDir(new File(filesDir, "logs"));
            File workspacesRoot = new File(filesDir, "DSH-workspaces");
            ensureDir(workspacesRoot);
            File legacyWorkspace = new File(filesDir, "workspace");
            File migratedWorkspace = new File(workspacesRoot, "workspace");
            if (legacyWorkspace.isDirectory() && !migratedWorkspace.exists()) {
                try {
                    java.nio.file.Files.move(legacyWorkspace.toPath(), migratedWorkspace.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    Log.i(TAG, "legacy workspace migrated to " + migratedWorkspace.getAbsolutePath());
                } catch (IOException e) {
                    Log.w(TAG, "legacy workspace migration failed: " + e.getMessage());
                }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", abiLibs.getAbsolutePath() + ":" + nativeDir);
            env.put("HOME", filesDir);
            env.put("DSH_HOME", dshHomeDir.getAbsolutePath());
            env.put("DSH_WORKSPACE", workspacesRoot.getAbsolutePath());
            env.put("DSH_WORKSPACES_ROOT", workspacesRoot.getAbsolutePath());
            env.put("NODE_PATH", nodeModules.getAbsolutePath());
            env.put("DSH_NODE_BIN", nodeBin.getAbsolutePath());
            env.put("DSH_PNPM_JS", filesDir + "/runtime/dsh/pnpm/pnpm.js");
            env.put("DSH_NPM_JS", filesDir + "/runtime/dsh/pnpm/npm.js");

            env.put("TMPDIR", filesDir + "/tmp");
            env.put("PATH", binDir.getAbsolutePath() + ":" + nativeDir + ":"
                    + abiLibs.getAbsolutePath() + ":/system/bin:/system/xbin");
            File caBundle = ensureCaBundle();
            if (caBundle != null) {
                env.put("NODE_EXTRA_CA_CERTS", caBundle.getAbsolutePath());
            }
            Log.i(TAG_NODE, "starting node: " + cmd);
            Log.i(TAG_NODE, "env: PATH=" + env.get("PATH") + " TMPDIR=" + env.get("TMPDIR")
                    + " LD_LIBRARY_PATH=" + env.get("LD_LIBRARY_PATH")
                    + " HOME=" + filesDir + " DSH_HOME=" + dshHomeDir
                    + " DSH_WORKSPACE=" + workspacesRoot
                    + " NODE_PATH=" + nodeModules);

            nodeProcess = pb.start();
            notifyState("node 已启动", "DSH web 服务正在监听 :3080");

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nodeProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i(TAG_NODE, line);
                if (line.startsWith(WEB_URL_PREFIX)) {
                    writeWebUrl(line.substring(WEB_URL_PREFIX.length()).trim());
                }
            }

            int exitCode = nodeProcess.waitFor();
            Log.w(TAG_NODE, "node exited with code " + exitCode);
            writeWebUrl(null);
            nodeProcess = null;
            workerBusy.set(false);
            scheduleRestart(exitCode);
        } catch (Exception e) {
            Log.e(TAG, "node worker failed", e);
            writeWebUrl(null);
            notifyState("node 运行异常", String.valueOf(e.getMessage()));
            workerBusy.set(false);
            scheduleRestart(-1);
        }
    }

    private int prepareShellTools(String abi, String nativeDir, File binDir) throws IOException, ErrnoException {
        File shellRoot = new File(getFilesDir(), "shell");
        File symlinksFile = new File(shellRoot, abi + "/symlinks.txt");
        try {
            unzipAsset(SHELL_ASSET, shellRoot);
        } catch (IOException e) {
            Log.w(TAG_SHELL, "shell manifest refresh failed", e);
        }
        if (!symlinksFile.exists()) {
            Log.w(TAG_SHELL, "symlinks manifest missing for " + abi);
            return 0;
        }
        ensureDir(binDir);
        int created = 0;
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(symlinksFile)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("\uFEFF", "").replace("\r", "").trim();
                if (line.isEmpty()) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String tool = line.substring(0, tab).trim();
                String lib = line.substring(tab + 1).trim();
                if (tool.isEmpty() || lib.isEmpty()) continue;
                File link = new File(binDir, tool);
                if (link.exists()) {
                    skipped++;
                    continue;
                }
                File target = new File(nativeDir, lib);
                if (!target.exists()) {
                    Log.w(TAG_SHELL, "link target missing for " + tool + ": " + target);
                    continue;
                }
                try {
                    Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
                    link.setExecutable(true, false);
                    created++;
                } catch (ErrnoException e) {
                    Log.w(TAG_SHELL, "symlink failed for " + tool + ": " + e.getMessage());
                }
            }
        }
        Log.i(TAG_SHELL, "symlinks created=" + created + " skipped=" + skipped);
        return created;
    }

    private void runShellSelfTest(File binDir, String nativeDir, String abiLibsPath) {
        File bash = new File(binDir, "bash");
        if (!bash.exists()) {
            Log.i(TAG_SHELL, "SHELL-SELFTEST-FAIL bash missing at " + bash);
            return;
        }
        File bashDirect = new File(nativeDir, "libbash.so");
        shellSelfTest(bash, "bash-echo-symlink", "echo DSH_BASH_OK", nativeDir, abiLibsPath, binDir);
        if (bashDirect.exists()) {
            shellSelfTest(bashDirect, "bash-echo-direct", "echo DSH_BASH_OK", nativeDir, abiLibsPath, binDir);
        }
        shellSelfTest(bash, "pipe-grep", "ls /system/etc | grep -c .", nativeDir, abiLibsPath, binDir);
        shellSelfTest(bash, "sed-awk", "printf abcdef | sed s/abc/XYZ/ ; printf 'one two\\n' | awk '{print $2}'", nativeDir, abiLibsPath, binDir);
    }

    private void shellSelfTest(File bash, String name, String command, String nativeDir, String abiLibsPath, File binDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    bash.getAbsolutePath(), "-c", command);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", binDir.getAbsolutePath() + ":" + nativeDir + ":"
                    + abiLibsPath + ":/system/lib64:/system/lib:/system/xbin64");
            env.put("TMPDIR", new File(binDir.getParentFile(), "tmp").getAbsolutePath());
            env.put("PATH", binDir.getAbsolutePath() + ":" + nativeDir + ":/system/bin:/system/xbin");
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() < 300) {
                    if (out.length() > 0) out.append(" | ");
                    out.append(line);
                }
            }
            int exit = p.waitFor();
            if (exit == 0) {
                Log.i(TAG_SHELL, "SHELL-SELFTEST-OK " + name + " -> " + out);
            } else {
                Log.i(TAG_SHELL, "SHELL-SELFTEST-FAIL " + name + " exit=" + exit + " -> " + out);
            }
        } catch (Exception e) {
            Log.i(TAG_SHELL, "SHELL-SELFTEST-FAIL " + name + " -> " + e.getMessage());
        }
    }

    private void scheduleRestart(int lastExitCode) {
        if (serviceStopping.get()) {
            Log.i(TAG, "service stopping; no restart");
            return;
        }
        autoRestartCount++;
        if (autoRestartCount > MAX_AUTO_RESTARTS) {
            Log.w(TAG, "restart limit reached (" + MAX_AUTO_RESTARTS + "), giving up");
            notifyState("node 已退出", "exit=" + lastExitCode + "，自动重启次数达上限");
            return;
        }
        Log.i(TAG, "restarting node in " + RESTART_DELAY_MS + "ms (attempt "
                + autoRestartCount + "/" + MAX_AUTO_RESTARTS + ")");
        mainHandler.postDelayed(() -> {
            if (!serviceStopping.get() && workerBusy.compareAndSet(false, true)) {
                executor.execute(this::runNodeLoop);
            }
        }, RESTART_DELAY_MS);
    }

    private void unzipAsset(String assetPath, File targetDir) throws IOException {
        Log.i(TAG, "extracting " + assetPath + " -> " + targetDir.getAbsolutePath());
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("cannot create " + targetDir);
        }
        String targetCanonical = targetDir.getCanonicalPath();
        int fileCount = 0;
        int dirCount = 0;
        try (InputStream raw = getAssets().open(assetPath);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw, 1 << 16))) {
            byte[] buffer = new byte[1 << 16];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                File outFile = new File(targetDir, name);
                String canonical = outFile.getCanonicalPath();
                if (!canonical.equals(targetCanonical)
                        && !canonical.startsWith(targetCanonical + File.separator)) {
                    Log.w(TAG, "skip zip-slip entry: " + entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    ensureDir(outFile);
                    dirCount++;
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) ensureDir(parent);
                    try (OutputStream os = new FileOutputStream(outFile)) {
                        int n;
                        while ((n = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, n);
                        }
                    }
                    fileCount++;
                    if (fileCount % 200 == 0) {
                        Log.i(TAG, "extraction progress: " + fileCount + " files...");
                    }
                }
                try {
                    zis.closeEntry();
                } catch (IOException ignored) {

                }
            }
        }
        Log.i(TAG, "extraction done: " + fileCount + " files, " + dirCount + " dirs -> "
                + targetCanonical);
    }

    private void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "mkdirs failed: " + dir);
        }
    }

    private void writeWebUrl(String url) {
        File file = new File(getFilesDir(), "weburl.txt");
        if (url == null) {
            if (file.exists() && file.delete()) {
                Log.i(TAG_NODE, "weburl.txt cleared");
            }
            return;
        }
        try (FileWriter fw = new FileWriter(file, false)) {
            fw.write(url);
            Log.i(TAG_NODE, "weburl.txt written: " + url);
        } catch (IOException e) {
            Log.e(TAG_NODE, "write weburl.txt failed", e);
        }
    }

    private File ensureCaBundle() {
        File bundle = new File(getFilesDir(), CA_BUNDLE_NAME);
        if (bundle.exists() && bundle.length() > 0) {
            return bundle;
        }
        File[] certs = new File(SYSTEM_CA_DIR).listFiles();
        if (certs == null || certs.length == 0) {
            Log.w(TAG, "no system CA certs at " + SYSTEM_CA_DIR);
            return null;
        }
        Arrays.sort(certs);
        try (FileOutputStream fos = new FileOutputStream(bundle)) {
            byte[] newline = "\n".getBytes();
            for (File cert : certs) {
                byte[] pem = readAllBytes(cert);
                if (pem == null || pem.length == 0) continue;
                fos.write(pem);
                fos.write(newline);
            }
            Log.i(TAG, "CA bundle built: " + certs.length + " certs -> " + bundle.getAbsolutePath());
            return bundle;
        } catch (IOException e) {
            Log.e(TAG, "CA bundle build failed", e);
            return null;
        }
    }

    private byte[] readAllBytes(File file) {
        try (InputStream in = new FileInputStream(file);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void notifyState(String title, String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(UPDATE_NOTIFICATION_ID, buildNotification(title, text));
            }
        } catch (Exception e) {
            Log.e(TAG, "notify failed", e);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
