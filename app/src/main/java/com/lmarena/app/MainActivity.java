package com.lmarena.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.lmarena.app.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LMArena";
    private static final String DIRECT_URL = "https://arena.ai/text/direct";
    private static final String AGENT_URL  = "https://arena.ai/agent/";
    private static final String BASE_URL   = "https://arena.ai";
    private static final String PREFS_NAME = "lmarena_prefs";

    private ActivityMainBinding binding;
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private SharedPreferences prefs;

    // Current chat mode: "direct" or "agent"
    private String chatMode = "direct";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge with dark background
        getWindow().setStatusBarColor(Color.parseColor("#0a0a0a"));
        getWindow().setNavigationBarColor(Color.parseColor("#0a0a0a"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                wic.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        chatMode = prefs.getString("chat_mode", "direct");

        webView      = binding.webView;
        swipeRefresh = binding.swipeRefresh;

        setupSwipeRefresh();
        setupWebView();
        setupBottomBar();

        // Restore cookies across sessions
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        loadCurrentMode();
    }

    // ── SwipeRefresh ─────────────────────────────────────────────────
    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(Color.parseColor("#7c6fcd"));
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1a1a1a"));
        swipeRefresh.setOnRefreshListener(() -> {
            webView.reload();
        });
    }

    // ── WebView setup ─────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Desktop UA override so arena.ai serves the full app (not a stripped mobile page)
        // We keep a mobile UA but append "Desktop" hint so we still pass mobile checks
        String ua = ws.getUserAgentString();
        ws.setUserAgentString(ua + " LMArenaAndroid/1.0");

        webView.addJavascriptInterface(new ArenaJsBridge(), "ArenaAndroid");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) {
                    swipeRefresh.setRefreshing(false);
                    // Inject mobile helper JS after page fully loaded
                    injectMobileHelpers();
                } else {
                    swipeRefresh.setRefreshing(true);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Allow arena.ai and google auth inside WebView
                if (url.contains("arena.ai") ||
                        url.contains("accounts.google.com") ||
                        url.contains("lmarena.ai") ||
                        url.contains("google.com/recaptcha")) {
                    return false; // load in webview
                }
                // Everything else: let system handle (open browser)
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Open the mobile sidebar so Log in button is accessible
                injectSidebarReveal();
                updateModeButtons();
            }
        });
    }

    // ── Bottom navigation bar ─────────────────────────────────────────
    private void setupBottomBar() {
        binding.btnDirect.setOnClickListener(v -> switchMode("direct"));
        binding.btnAgent.setOnClickListener(v -> switchMode("agent"));
        binding.btnReload.setOnClickListener(v -> {
            webView.reload();
            Toast.makeText(this, "새로고침 중...", Toast.LENGTH_SHORT).show();
        });
        updateModeButtons();
    }

    private void switchMode(String mode) {
        if (mode.equals(chatMode)) return;
        chatMode = mode;
        prefs.edit().putString("chat_mode", mode).apply();
        loadCurrentMode();
        updateModeButtons();
    }

    private void updateModeButtons() {
        boolean isDirect = "direct".equals(chatMode);
        binding.btnDirect.setAlpha(isDirect ? 1.0f : 0.45f);
        binding.btnAgent.setAlpha(isDirect ? 0.45f : 1.0f);
    }

    private void loadCurrentMode() {
        String url = "direct".equals(chatMode) ? DIRECT_URL : AGENT_URL;
        webView.loadUrl(url);
    }

    // ── JS injection helpers ──────────────────────────────────────────

    /**
     * Injects CSS & JS tweaks that make the LM Arena web UI comfortable on mobile:
     * - hides desktop-only sidebar toggle/nav that overlap content
     * - ensures the composer textarea is always reachable
     * - taps the "Open sidebar" button so the Log in link appears (mobile DOM)
     */
    private void injectMobileHelpers() {
        String js = "(function() {" +
            // ── 1. Open sidebar so Login button is visible on mobile ──
            "  var sidebarBtn = document.querySelector(" +
            "    'button[aria-label=\"Open sidebar\"]');" +
            "  if (sidebarBtn) { sidebarBtn.click(); }" +

            // ── 2. Remove the fixed bottom safe-area padding that hides the composer ──
            "  document.querySelectorAll('[class*=\"pb-safe\"]').forEach(function(el) {" +
            "    el.style.paddingBottom = '0px';" +
            "  });" +

            // ── 3. Shrink oversized top header on mobile so chat area is taller ──
            "  var header = document.querySelector('header');" +
            "  if (header) { header.style.minHeight = '48px'; }" +

            // ── 4. Inject our status indicator if not already there ──
            "  if (!document.getElementById('lma-status')) {" +
            "    var bar = document.createElement('div');" +
            "    bar.id = 'lma-status';" +
            "    bar.style.cssText = 'position:fixed;bottom:56px;left:0;right:0;" +
            "      height:2px;background:#7c6fcd;opacity:0;transition:opacity .3s;z-index:9999;';" +
            "    document.body.appendChild(bar);" +
            "  }" +

            // ── 5. Watch for generation indicator (stop button) and report to native ──
            "  if (!window.__lmaObserverRunning) {" +
            "    window.__lmaObserverRunning = true;" +
            "    var bar = document.getElementById('lma-status');" +
            "    var lastGenerating = false;" +
            "    setInterval(function() {" +
            "      var stopBtn = document.querySelector(" +
            "        \"button[aria-label='Stop generation'],\" +" +
            "        \"button:has(svg rect[width='12'][height='12'])\");" +
            "      var generating = !!stopBtn;" +
            "      if (bar) bar.style.opacity = generating ? '1' : '0';" +
            "      if (generating !== lastGenerating) {" +
            "        lastGenerating = generating;" +
            "        if (window.ArenaAndroid) {" +
            "          ArenaAndroid.onGenerationStateChanged(generating);" +
            "        }" +
            "      }" +
            "    }, 600);" +
            "  }" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    /**
     * On pages that need it, tap the sidebar button so the Log in button appears
     * (mobile DOM difference noted in spec).
     */
    private void injectSidebarReveal() {
        String js = "(function() {" +
            "  var btn = document.querySelector('button[aria-label=\"Open sidebar\"]');" +
            "  if (btn) { btn.click(); }" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    // ── JS ↔ Native bridge ────────────────────────────────────────────
    public class ArenaJsBridge {
        @JavascriptInterface
        public void onGenerationStateChanged(boolean generating) {
            runOnUiThread(() -> {
                // Could update a native indicator if desired
                Log.d(TAG, "Generation: " + generating);
            });
        }

        @JavascriptInterface
        public void log(String msg) {
            Log.d(TAG, "JS: " + msg);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        // Persist cookies
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
