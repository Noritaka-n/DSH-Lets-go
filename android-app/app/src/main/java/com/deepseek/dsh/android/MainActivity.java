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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
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
    private static final int WEB_PORT = 3080;
    private static final String WEB_BASE_FILE = "webbase.txt";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout rootLayout;
    private TextView statusView;
    private Button keepAliveButton;
    private WebView webView;
    private volatile boolean pageLoaded = false;
    private volatile String loadedUrl = null;

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
                        webView.loadUrl(url);
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

        requestPostNotificationsIfNeeded();

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setText("DSH 启动中…\n\n正在等待本地 web 运行时就绪 (127.0.0.1:3080)");
        statusView.setTextSize(18f);
        statusView.setTextColor(Color.WHITE);
        statusView.setBackgroundColor(Color.BLACK);
        statusView.setGravity(Gravity.CENTER);
        statusView.setVisibility(View.VISIBLE);
        rootLayout.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        keepAliveButton = new Button(this);
        keepAliveButton.setText("后台保活设置（允许自启动 + 电池不限制）");
        keepAliveButton.setOnClickListener(v -> openOemAutoStartSettings());
        keepAliveButton.setVisibility(View.VISIBLE);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        keepAliveButton.setPadding(pad, pad / 2, pad, pad / 2);
        rootLayout.addView(keepAliveButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        WebView.setWebContentsDebuggingEnabled(true);
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
        applyImmersiveMode();

        Intent service = new Intent(this, DshService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }

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
        statusView.setVisibility(waiting ? View.VISIBLE : View.GONE);
        keepAliveButton.setVisibility(waiting ? View.VISIBLE : View.GONE);
        webView.setVisibility(waiting ? View.GONE : View.VISIBLE);
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
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
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
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
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
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(urlPoller);
        super.onDestroy();
    }
}
