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
        // Keep JS/timers alive so active agent streams and WebSockets are uninterrupted in background
    }

    public static void trimMemory(int level, Handler handler, WebViewHost host) {
        // Intentionally do NOT call deprecated WebView.freeMemory(): on Xiaomi HyperOS,
        // background trim callbacks fire immediately on minimize and calling freeMemory()
        // purges V8 bytecode/GPU tiles, causing a massive CPU/RAM spike and reload on resume.
    }

    public static void destroy(Handler handler) {
        handler.removeCallbacksAndMessages(null);
    }
}
