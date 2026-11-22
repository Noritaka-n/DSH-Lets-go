package com.deepseek.dsh.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout rootLayout;
    private TextView statusView;
    private WebView webView;
    private volatile boolean pageLoaded = false;
    private volatile String loadedUrl = null;

    private final Runnable urlPoller = new Runnable() {
        @Override
        public void run() {
            if (pageLoaded) {
                return;
            }
            final String url = readWebUrl();
            if (url == null) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
                return;
            }

            new Thread(() -> {
                final boolean up = isPortListening(3080);
                handler.post(() -> {
                    if (pageLoaded) {
                        return;
                    }
                    if (up) {
                        loadedUrl = url;
                        pageLoaded = true;
                        statusView.setVisibility(View.GONE);
                        webView.setVisibility(View.VISIBLE);
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
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {

                if (request.isForMainFrame()) {
                    pageLoaded = false;
                    loadedUrl = null;
                    statusView.setVisibility(View.VISIBLE);
                    webView.setVisibility(View.GONE);
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

        handler.postDelayed(urlPoller, POLL_INTERVAL_MS);
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

    private boolean isPortListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String readWebUrl() {
        File file = new File(getFilesDir(), "weburl.txt");
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
            android.util.Log.w("DshMain", "read weburl.txt failed", e);
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
