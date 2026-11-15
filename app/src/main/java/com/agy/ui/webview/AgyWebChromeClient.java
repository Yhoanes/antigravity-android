package com.agy.ui.webview;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.agy.bridge.AndroidBridge;
import com.agy.ui.MainActivity;
import com.agy.util.LoopbackHost;

import java.util.ArrayList;
import java.util.List;

/** Routes console output to logcat, popups to PopupWindowHandler, file chooser, and mic permissions. */
public final class AgyWebChromeClient extends WebChromeClient {

    private static final String TAG = "WebViewHost";

    public interface FileChooserCallback {
        boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                  FileChooserParams fileChooserParams);
    }

    private final PopupWindowHandler popupHandler;
    private final AndroidBridge bridge;
    private final FileChooserCallback fileChooserCallback;

    public AgyWebChromeClient(PopupWindowHandler popupHandler, AndroidBridge bridge,
                              FileChooserCallback fileChooserCallback) {
        this.popupHandler = popupHandler;
        this.bridge = bridge;
        this.fileChooserCallback = fileChooserCallback;
    }

    public AgyWebChromeClient(PopupWindowHandler popupHandler, AndroidBridge bridge) {
        this(popupHandler, bridge, null);
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage cm) {
        if (cm != null) {
            String msg = cm.message();
            String src = cm.sourceId();
            int line = cm.lineNumber();
            ConsoleMessage.MessageLevel lvl = cm.messageLevel();
            String tag = "AGY_JS";
            if (lvl == ConsoleMessage.MessageLevel.ERROR) {
                Log.e(tag, "[" + src + ":" + line + "] " + msg);
            } else if (lvl == ConsoleMessage.MessageLevel.WARNING) {
                Log.w(tag, "[" + src + ":" + line + "] " + msg);
            } else {
                Log.i(tag, "[" + src + ":" + line + "] " + msg);
            }
        }
        return true;
    }

    @Override
    public void onPermissionRequest(final PermissionRequest request) {
        if (request == null) {
            return;
        }
        try {
            Uri origin = request.getOrigin();
            int enginePort = bridge != null ? bridge.getEnginePort() : -1;
            boolean trustedHostOrigin = origin != null
                    && "https".equalsIgnoreCase(origin.getScheme())
                    && LoopbackHost.isLoopback(origin.getHost())
                    && enginePort > 0
                    && origin.getPort() == enginePort;
            if (!trustedHostOrigin) {
                Log.w(TAG, "Denied WebView permission request from untrusted origin");
                request.deny();
                return;
            }

            List<String> allowed = new ArrayList<>();
            String[] resources = request.getResources();
            if (resources != null) {
                for (String r : resources) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                        allowed.add(r);
                    }
                }
            }
            if (allowed.isEmpty()) {
                request.deny();
                return;
            }

            MainActivity act = MainActivity.activeActivity;
            if (act != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && act.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                act.requestWebViewAudioPermission(request);
                return;
            }
            request.grant(allowed.toArray(new String[0]));
        } catch (Throwable t) {
            Log.w(TAG, "onPermissionRequest failed: " + t.getMessage());
            try {
                request.deny();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public boolean onCreateWindow(WebView parent, boolean isDialog, boolean isUserGesture,
                                  Message resultMsg) {
        return popupHandler.handle(parent, isDialog, isUserGesture, resultMsg);
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                      FileChooserParams fileChooserParams) {
        if (fileChooserCallback != null) {
            return fileChooserCallback.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }
        return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
    }
}
