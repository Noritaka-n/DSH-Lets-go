package com.deepseek.dsh.android;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends Activity {

    private static final long POLL_INTERVAL_MS = 2000;
    private static final int REQUEST_POST_NOTIFICATIONS = 1;
    private static final int REQUEST_STORAGE_TREE = 2;
    private static final int REQUEST_STORAGE_PERMISSIONS = 3;
    private static final int WEB_PORT = 3080;
    private static final String WEB_BASE_FILE = "webbase.txt";
    public static final String ACTION_OPEN_SESSION = "com.deepseek.dsh.android.OPEN_SESSION";
    public static final String EXTRA_SESSION_ID = "dsh_session_id";
    public static final String EXTRA_TASK_ID = "dsh_task_id";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout rootLayout;
    private LinearLayout settingsLayout;
    private TextView statusView;
    private TextView settingsStatusView;
    private Button keepAliveButton;
    private Button apiKeyButton;
    private Button saveApiKeyButton;
    private Button storageButton;
    private Button settingsStorageButton;
    private EditText apiKeyInput;
    private WebView webView;
    private boolean awaitingStoragePermission;
    private volatile boolean pageLoaded = false;
    private volatile String loadedUrl = null;
    private volatile String pendingSessionId = null;

    private final Runnable urlPoller = new Runnable() {
        @Override
        public void run() {
            if (pageLoaded) {
                return;
            }
            final String url = readPreferredLaunchUrl();
            if (url == null) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
                return;
            }
            new Thread(() -> {
                final boolean up = isPortListening(WEB_PORT);
                handler.post(() -> {
                    if (pageLoaded) {
                        return;
                    }
                    if (up) {
                        loadedUrl = url;
                        pageLoaded = true;
                        setWaiting(false);
                        if (webView != null) {
                            webView.loadUrl(url);
                        }
                    } else {
                        handler.postDelayed(urlPoller, POLL_INTERVAL_MS);
                    }
                });
            }, "dsh-port-probe").start();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        captureSessionIntent(getIntent());
        requestPostNotificationsIfNeeded();

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        rootLayout.setBackgroundColor(Color.rgb(12, 30, 52));

        statusView = new TextView(this);
        statusView.setText("正在启动…");
        statusView.setTextSize(15f);
        statusView.setTextColor(Color.parseColor("#B3FFFFFF"));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(pad, pad, pad, pad);
        statusView.setVisibility(View.VISIBLE);
        rootLayout.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        apiKeyButton = new Button(this);
        apiKeyButton.setText("API Key 设置");
        apiKeyButton.setOnClickListener(v -> showApiKeySettings());

        storageButton = new Button(this);
        storageButton.setOnClickListener(v -> openStorageTreePicker());

        keepAliveButton = new Button(this);
        keepAliveButton.setText("后台保活设置（允许自启动 + 电池不限制）");
        keepAliveButton.setOnClickListener(v -> openOemAutoStartSettings());
        keepAliveButton.setPadding(pad, pad / 2, pad, pad / 2);

        settingsLayout = new LinearLayout(this);
        settingsLayout.setOrientation(LinearLayout.VERTICAL);
        settingsLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        settingsLayout.setBackgroundColor(Color.WHITE);
        settingsLayout.setPadding(pad * 2, pad * 3, pad * 2, pad * 2);
        settingsLayout.setVisibility(View.GONE);

        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("API Key 设置");
        settingsTitle.setTextSize(24f);
        settingsTitle.setTextColor(Color.BLACK);
        settingsTitle.setGravity(Gravity.CENTER);
        settingsLayout.addView(settingsTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("输入 DeepSeek API Key");
        apiKeyInput.setTextColor(Color.BLACK);
        apiKeyInput.setHintTextColor(Color.GRAY);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, pad * 2, 0, 0);
        settingsLayout.addView(apiKeyInput, inputParams);

        settingsStatusView = new TextView(this);
        settingsStatusView.setText("密钥由 Android Keystore 加密，仅保存在本机");
        settingsStatusView.setTextSize(15f);
        settingsStatusView.setTextColor(Color.DKGRAY);
        settingsStatusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams settingsStatusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsStatusParams.setMargins(0, pad, 0, pad);
        settingsLayout.addView(settingsStatusView, settingsStatusParams);

        saveApiKeyButton = new Button(this);
        saveApiKeyButton.setText("保存 API Key 并启动");
        saveApiKeyButton.setOnClickListener(v -> saveApiKeyAndStart());
        settingsLayout.addView(saveApiKeyButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        settingsStorageButton = new Button(this);
        settingsStorageButton.setOnClickListener(v -> openStorageTreePicker());
        settingsLayout.addView(settingsStorageButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button settingsKeepAliveButton = new Button(this);
        settingsKeepAliveButton.setText("后台保活设置");
        settingsKeepAliveButton.setOnClickListener(v -> openOemAutoStartSettings());
        settingsLayout.addView(settingsKeepAliveButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rootLayout.addView(settingsLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null || !url.startsWith("http://127.0.0.1:" + WEB_PORT)) {
                    return;
                }
                if (url.contains("token=")) {
                    return;
                }
                view.evaluateJavascript(
                        "(document.body && document.body.innerText.indexOf('authentication required')>=0)?'1':'0'",
                        v -> {
                            boolean authRequired = v != null
                                    && "1".equals(v.replace("\"", "").trim());
                            if (authRequired) {
                                new File(getFilesDir(), WEB_BASE_FILE).delete();
                                Log.i("DshMain", "cookie expired; re-auth via token url");
                                String tok = readSingleLineFile("weburl.txt");
                                if (tok != null) {
                                    view.loadUrl(tok);
                                }
                                return;
                            }
                            int q = url.indexOf('?');
                            writeWebBase(q > 0 ? url.substring(0, q) : url);
                            consumePendingSession(view);
                        });
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageLoaded = false;
                    loadedUrl = null;
                    setWaiting(true);
                    handler.removeCallbacks(urlPoller);
                    handler.postDelayed(urlPoller, POLL_INTERVAL_MS);
                }
            }
        });
        webView.setVisibility(View.GONE);
        rootLayout.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(rootLayout);
        updateStorageButtons();
        applyImmersiveMode();

        startDshService();
        requestIgnoreBatteryOptimizationsIfNeeded();

        if (savedInstanceState != null && savedInstanceState.getBoolean("pageLoaded", false)) {
            loadedUrl = savedInstanceState.getString("loadedUrl");
            pageLoaded = true;
            setWaiting(false);
            boolean restoredState = false;
            try {
                restoredState = webView.restoreState(savedInstanceState) != null;
            } catch (Exception ignored) {
            }
            final boolean restored = restoredState;
            new Thread(() -> {
                final boolean up = isPortListening(WEB_PORT);
                handler.post(() -> {
                    if (up) {
                        if (!restored) {
                            String u = readPreferredLaunchUrl();
                            if (u != null) {
                                webView.loadUrl(u);
                            }
                        }
                    } else {
                        pageLoaded = false;
                        setWaiting(true);
                        handler.removeCallbacks(urlPoller);
                        handler.postDelayed(urlPoller, POLL_INTERVAL_MS);
                    }
                });
            }, "dsh-restore-probe").start();
            return;
        }

        handler.postDelayed(urlPoller, POLL_INTERVAL_MS);
    }

    private void setWaiting(boolean waiting) {
        settingsLayout.setVisibility(View.GONE);
        statusView.setVisibility(waiting ? View.VISIBLE : View.GONE);
        if (webView != null) {
            webView.setVisibility(waiting ? View.GONE : View.VISIBLE);
        }
    }

    private void showApiKeySettings() {
        handler.removeCallbacks(urlPoller);
        statusView.setVisibility(View.GONE);
        apiKeyButton.setVisibility(View.GONE);
        storageButton.setVisibility(View.GONE);
        keepAliveButton.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        settingsLayout.setVisibility(View.VISIBLE);
        boolean hasKey = hasStoredApiKey();
        settingsStatusView.setText(hasKey
                ? "已使用 Android Keystore 加密保存，输入新密钥可替换"
                : "密钥由 Android Keystore 加密，仅保存在本机");
        saveApiKeyButton.setText(hasKey ? "保存新 API Key 并重启" : "保存 API Key 并启动");
    }

    private void saveApiKeyAndStart() {
        String apiKey = apiKeyInput.getText().toString().trim();
        if (apiKey.isEmpty()) {
            settingsStatusView.setText("请输入 API Key");
            return;
        }
        saveApiKeyButton.setEnabled(false);
        try {
            new CredentialStore(this).saveApiKey(apiKey);
            apiKeyInput.setText("");
            pageLoaded = false;
            loadedUrl = null;
            setWaiting(true);
            statusView.setText("API Key 已加密保存\n\n正在启动本地 web 运行时…");
            stopService(new Intent(this, DshService.class));
            startDshService();
            requestIgnoreBatteryOptimizationsIfNeeded();
            handler.removeCallbacks(urlPoller);
            handler.postDelayed(urlPoller, POLL_INTERVAL_MS);
        } catch (Exception e) {
            Log.w("DshMain", "API key save failed", e);
            settingsStatusView.setText("保存失败：" + e.getClass().getSimpleName());
        } finally {
            saveApiKeyButton.setEnabled(true);
        }
    }

    private boolean hasStoredApiKey() {
        try {
            String value = new CredentialStore(this).getApiKey();
            return value != null && !value.isEmpty();
        } catch (Exception e) {
            Log.w("DshMain", "Keystore credential read failed", e);
            return false;
        }
    }

    private boolean hasLegacyCredentials() {
        return new File(getFilesDir(), "dsh-home/.credentials.yaml").isFile();
    }

    private void startDshService() {
        Intent service = new Intent(this, DshService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private void openStorageTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_STORAGE_TREE);
    }

    private void ensureStorageAccessAndRestart() {
        if (new SafStore(this).getWorkspaceRoot() == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            awaitingStoragePermission = true;
            showStorageStatus("存储位置已保存\n\n请在系统设置中允许 DSH 管理所有文件，供 node 使用直接路径读写");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Log.w("DshMain", "package all-files settings unavailable; trying global page", e);
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception fallback) {
                    Log.w("DshMain", "global all-files settings unavailable", fallback);
                    showStorageStatus("请在应用详情中允许 DSH 管理所有文件，然后返回应用");
                }
            }
            return;
        }
        if (Build.VERSION.SDK_INT == 29 && !hasRawStorageAccess()) {
            awaitingStoragePermission = true;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSIONS);
            return;
        }
        applyStorageSelection();
    }

    private void applyStorageSelection() {
        try {
            String path = new SafStore(this).verifySelection();
            awaitingStoragePermission = false;
            updateStorageButtons();
            if (hasStoredApiKey() || hasLegacyCredentials()) {
                pageLoaded = false;
                loadedUrl = null;
                setWaiting(true);
                statusView.setText("工作区存储位置已生效\n\n" + path + "\n\n正在重启本地运行时…");
                stopService(new Intent(this, DshService.class));
                startDshService();
                handler.removeCallbacks(urlPoller);
                handler.postDelayed(urlPoller, POLL_INTERVAL_MS);
            } else {
                showApiKeySettings();
                settingsStatusView.setText("存储位置已设置：" + path + "\n保存 API Key 后启动");
            }
        } catch (Exception e) {
            Log.w("DshMain", "SAF workspace verification failed", e);
            showStorageStatus("存储位置暂不可写\n\n" + e.getClass().getSimpleName()
                    + "\n请重新选择目录并允许文件管理权限");
        }
    }

    private boolean hasRawStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateStorageButtons() {
        if (storageButton == null || settingsStorageButton == null) {
            return;
        }
        SafStore store = new SafStore(this);
        String text = store.getTreeUri() == null
                ? "选择存储位置（默认应用私有目录）"
                : "存储位置：" + store.getSelectionLabel();
        storageButton.setText(text);
        settingsStorageButton.setText(text);
    }

    private void showStorageStatus(String message) {
        if (settingsLayout.getVisibility() == View.VISIBLE) {
            settingsStatusView.setText(message);
        } else {
            statusView.setText(message);
            statusView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_STORAGE_TREE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        try {
            new SafStore(this).saveSelection(data.getData(), data.getFlags());
            updateStorageButtons();
            ensureStorageAccessAndRestart();
        } catch (Exception e) {
            Log.w("DshMain", "SAF selection failed", e);
            showStorageStatus("存储位置保存失败：" + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSIONS) {
            if (hasRawStorageAccess()) {
                applyStorageSelection();
            } else {
                showStorageStatus("请允许外部存储读写权限后再使用所选工作区");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        updateStorageButtons();
        if (awaitingStoragePermission) {
            if (hasRawStorageAccess()) {
                applyStorageSelection();
            } else {
                showStorageStatus("请在系统设置中允许 DSH 管理所有文件，然后返回应用");
            }
        }
        if (!pageLoaded && settingsLayout.getVisibility() != View.VISIBLE) {
            handler.removeCallbacks(urlPoller);
            handler.postDelayed(urlPoller, POLL_INTERVAL_MS);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("pageLoaded", pageLoaded);
        if (loadedUrl != null) {
            outState.putString("loadedUrl", loadedUrl);
        }
        if (webView != null && pageLoaded) {
            webView.saveState(outState);
        }
    }

    private void applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && Build.VERSION.SDK_INT < 30) {
            applyImmersiveMode();
        }
    }

    private void requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                return;
            }
            android.content.SharedPreferences prefs =
                    getSharedPreferences("dsh_ui", MODE_PRIVATE);
            if (prefs.getBoolean("battery_prompted", false)) {
                return;
            }
            prefs.edit().putBoolean("battery_prompted", true).apply();
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Log.w("DshMain", "battery optimization dialog unavailable", e);
        }
    }

    private void openOemAutoStartSettings() {
        String mfr = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        ComponentName[] candidates;
        if (mfr.contains("xiaomi") || mfr.contains("redmi")) {
            candidates = new ComponentName[]{
                    new ComponentName("com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity")};
        } else if (mfr.contains("oppo") || mfr.contains("realme") || mfr.contains("oneplus")) {
            candidates = new ComponentName[]{
                    new ComponentName("com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    new ComponentName("com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity")};
        } else if (mfr.contains("vivo") || mfr.contains("iqoo")) {
            candidates = new ComponentName[]{
                    new ComponentName("com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")};
        } else if (mfr.contains("huawei") || mfr.contains("honor")) {
            candidates = new ComponentName[]{
                    new ComponentName("com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    new ComponentName("com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity")};
        } else if (mfr.contains("samsung")) {
            candidates = new ComponentName[]{
                    new ComponentName("com.samsung.android.lool",
                            "com.samsung.android.sm.battery.ui.BatteryActivity")};
        } else {
            candidates = new ComponentName[0];
        }
        for (ComponentName cn : candidates) {
            try {
                startActivity(new Intent().setComponent(cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Log.w("DshMain", "app details page unavailable", e);
        }
    }

    private boolean isPortListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String readPreferredLaunchUrl() {
        String base = readSingleLineFile(WEB_BASE_FILE);
        if (base != null) {
            return base;
        }
        return readSingleLineFile("weburl.txt");
    }

    private void writeWebBase(String baseUrl) {
        try {
            new File(getFilesDir(), "tmp").mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(
                    new File(getFilesDir(), WEB_BASE_FILE));
            fos.write(baseUrl.getBytes("UTF-8"));
            fos.close();
            Log.i("DshMain", "webbase saved: " + baseUrl);
        } catch (IOException e) {
            Log.w("DshMain", "write webbase failed", e);
        }
    }

    private String readSingleLineFile(String name) {
        File file = new File(getFilesDir(), name);
        if (!file.exists()) {
            return null;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line = reader.readLine();
            if (line != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    return line;
                }
            }
        } catch (IOException e) {
            Log.w("DshMain", "read " + name + " failed", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (settingsLayout.getVisibility() == View.VISIBLE) {
            settingsLayout.setVisibility(View.GONE);
            if (pageLoaded && webView != null) {
                statusView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            } else {
                setWaiting(true);
                handler.removeCallbacks(urlPoller);
                handler.postDelayed(urlPoller, POLL_INTERVAL_MS);
            }
            return;
        }
        if (webView != null && webView.getVisibility() == View.VISIBLE
                && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureSessionIntent(intent);
        if (webView != null && pageLoaded) {
            consumePendingSession(webView);
        }
    }

    private void captureSessionIntent(Intent intent) {
        if (intent == null || !ACTION_OPEN_SESSION.equals(intent.getAction())) {
            return;
        }
        String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            pendingSessionId = sessionId.trim();
            Log.i("DshMain", "session deep link queued: " + pendingSessionId);
        }
    }

    private void consumePendingSession(WebView view) {
        final String sessionId = pendingSessionId;
        if (sessionId == null || view == null) {
            return;
        }
        pendingSessionId = null;
        String quoted = sessionId.replace("\\", "\\\\").replace("\"", "\\\"");
        String script = "(function(){"
                + "var id=\"" + quoted + "\";"
                + "function find(){"
                + "return document.querySelector('[data-row-key=\"'+id+'\"]')"
                + "||document.querySelector('[data-session-id=\"'+id+'\"]')"
                + "||document.querySelector('[data-row-key^=\"'+id+'\"]');"
                + "}"
                + "var el=find();"
                + "if(el){el.click();return 'clicked';}"
                + "if(window.__DSH_OPEN_SESSION__){window.__DSH_OPEN_SESSION__(id);return 'api';}"
                + "return 'missing';"
                + "})()";
        view.evaluateJavascript(script, v -> {
            Log.i("DshMain", "session deep link result: " + v);
        });
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(urlPoller);
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
