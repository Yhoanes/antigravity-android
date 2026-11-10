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

import com.agy.ui.MainActivity;

/** Routes console output to logcat, popups to PopupWindowHandler, file chooser, and mic permissions. */
public final class AgyWebChromeClient extends WebChromeClient {

    private static final String TAG = "WebViewHost";

    public interface FileChooserCallback {
        boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                  FileChooserParams fileChooserParams);
    }

    private final PopupWindowHandler popupHandler;
    private final FileChooserCallback fileChooserCallback;

    public AgyWebChromeClient(PopupWindowHandler popupHandler, FileChooserCallback fileChooserCallback) {
        this.popupHandler = popupHandler;
        this.fileChooserCallback = fileChooserCallback;
    }

    public AgyWebChromeClient(PopupWindowHandler popupHandler) {
        this(popupHandler, null);
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
        MainActivity act = MainActivity.activeActivity;
        if (act != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean needsAudio = false;
            String[] resources = request.getResources();
            if (resources != null) {
                for (String r : resources) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                        needsAudio = true;
                        break;
                    }
                }
            }
            if (needsAudio && act.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                act.requestWebViewAudioPermission(request);
                return;
            }
        }
        try {
            request.grant(request.getResources());
        } catch (Throwable t) {
            Log.w(TAG, "onPermissionRequest grant failed: " + t.getMessage());
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
