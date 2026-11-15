package com.agy.ui.webview;

import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;

import com.agy.util.LoopbackHost;

/** The core serves a self-signed cert on its loopback enginePort; proceed only there. */
public final class LoopbackSslPolicy {

    private static final String TAG = "WebViewHost";

    private LoopbackSslPolicy() {
    }

    public static boolean proceed(SslError error, int enginePort) {
        if (error == null || enginePort <= 0) {
            return false;
        }
        try {
            String errorUrl = error.getUrl();
            if (errorUrl != null) {
                Uri uri = Uri.parse(errorUrl);
                return LoopbackHost.isLoopback(uri.getHost()) && uri.getPort() == enginePort;
            }
        } catch (Exception e) {
            Log.w(TAG, "ssl url parse failed");
        }
        return false;
    }
}
