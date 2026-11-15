package com.agy.ui.webview;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.agy.bridge.AndroidBridge;
import com.agy.oauth.OAuthManager;
import com.agy.util.LoopbackHost;

/** WebView client: OAuth interception, loopback TLS, and static asset serving. */
public final class AgyWebViewClient extends WebViewClient {

    private static final String TAG = "WebViewHost";

    private final Context context;
    private final OAuthManager oauth;
    private final LocalAssetServer assetServer;
    private final AndroidBridge bridge;
    private final WebViewHost.Listener listener;

    public AgyWebViewClient(Context context, OAuthManager oauth, LocalAssetServer assetServer,
                            AndroidBridge bridge, WebViewHost.Listener listener) {
        this.context = context;
        this.oauth = oauth;
        this.assetServer = assetServer;
        this.bridge = bridge;
        this.listener = listener;
    }

    static String redactUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int cut = -1;
        if (q >= 0 && h >= 0) cut = Math.min(q, h);
        else if (q >= 0) cut = q;
        else if (h >= 0) cut = h;
        return cut >= 0 ? url.substring(0, cut) : url;
    }

    @Override
    public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
        if (req != null && req.getUrl() != null) {
            Log.w(TAG, "Resource load error: " + redactUrl(req.getUrl().toString()) + " err="
                    + (err != null ? err.getDescription() : "unknown"));
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
        String url = request != null && request.getUrl() != null
                ? request.getUrl().toString() : null;
        boolean mainFrame = request == null || request.isForMainFrame();
        Log.i(TAG, "shouldOverrideUrlLoading main=" + mainFrame + " url=" + redactUrl(url));
        return handleNavigation(v, url, mainFrame);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView v, String url) {
        Log.i(TAG, "shouldOverrideUrlLoading(deprecated) url=" + redactUrl(url));
        return handleNavigation(v, url, true);
    }

    private boolean handleNavigation(WebView v, String url, boolean isMainFrame) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            if (!LoopbackHost.isLoopback(uri)) {
                Log.i(TAG, "non-loopback navigation -> external browser: " + redactUrl(url));
                return oauth.openInBrowser(context, url);
            }
            int enginePort = bridge.getEnginePort();
            if (isMainFrame && enginePort > 0 && uri.getPort() != enginePort) {
                Log.i(TAG, "non-engine loopback main-frame navigation -> external browser: " + redactUrl(url));
                return oauth.openInBrowser(context, url);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot parse navigation uri");
        }
        return oauth.intercept(context, url);
    }

    /**
     * Server-side (3xx) redirects do not always reach shouldOverrideUrlLoading;
     * catch non-engine top-level URLs here and stop the WebView from rendering them.
     */
    @Override
    public void onPageStarted(WebView v, String url, Bitmap favicon) {
        if (url != null && !url.equals("about:blank")) {
            try {
                Uri uri = Uri.parse(url);
                int enginePort = bridge.getEnginePort();
                if (!LoopbackHost.isLoopback(uri)
                        || (enginePort > 0 && uri.getPort() != enginePort)) {
                    Log.i(TAG, "onPageStarted intercepted untrusted top-level url: " + redactUrl(url));
                    v.stopLoading();
                    oauth.openInBrowser(context, url);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        if (oauth.intercept(context, url)) {
            Log.i(TAG, "onPageStarted stopped auth url=" + redactUrl(url));
            v.stopLoading();
        }
    }

    @Override
    public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
        if (LoopbackSslPolicy.proceed(error, bridge.getEnginePort())) {
            handler.proceed();
        } else {
            handler.cancel();
        }
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        int enginePort = bridge.getEnginePort();
        WebResourceResponse assetResp = assetServer.intercept(
                context, request.getUrl(), request.getMethod(), enginePort);
        if (assetResp != null) {
            return assetResp;
        }
        return SidecarLoopbackProxy.intercept(
                request.getUrl(), request.getMethod(), request.getRequestHeaders(), enginePort);
    }

    @Override
    @SuppressWarnings("deprecation")
    public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
        if (url == null) {
            return null;
        }
        try {
            Uri uri = Uri.parse(url);
            int enginePort = bridge.getEnginePort();
            WebResourceResponse assetResp = assetServer.intercept(context, uri, "GET", enginePort);
            if (assetResp != null) {
                return assetResp;
            }
            return SidecarLoopbackProxy.intercept(uri, "GET", null, enginePort);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onPageFinished(WebView v, String url) {
        if (listener != null) {
            listener.onPageFinished(url);
        }
    }
}
