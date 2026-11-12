package com.agy.ui.lifecycle;

import android.content.ComponentCallbacks2;
import android.os.Handler;
import android.webkit.WebView;

import com.agy.ui.webview.WebViewHost;
import com.agy.ui.webview.WebViewLifecycle;

/** Activity lifecycle glue: WebView resume, trim-memory and handler teardown. */
public final class AppLifecycle {

    private AppLifecycle() {
    }

    public static void resume(WebView webView) {
        WebViewLifecycle.resume(webView);
    }

    public static void pauseTimers(WebView webView) {
        WebViewLifecycle.resumeTimers(webView);
    }

    public static void trimMemory(int level, Handler handler, WebViewHost host) {
        // Intentionally do not invoke deprecated WebView.freeMemory() on background trim:
        // switching to an external browser (e.g. OAuth login) or another app triggers
        // TRIM_MEMORY_MODERATE on Android/HyperOS, and freeMemory() purges active WebView
        // caches/pipes and stalls live agent state streams on resume.
    }

    public static void destroy(Handler handler) {
        handler.removeCallbacksAndMessages(null);
    }
}
