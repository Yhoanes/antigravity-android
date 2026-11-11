package com.agy.ui.webview;

import android.util.Log;

import com.agy.bridge.AndroidBridge;
import com.agy.theme.ThemeManager;

import org.json.JSONObject;

/** Injects the boot config and the startup_ready script ahead of the app UI. */
public final class AppHtmlInjector {

    private static final String TAG = "WebViewHost";

    /** Web layer generates the ready signal under generated/patches. */
    private static final String READY_SCRIPT = "./generated/patches/startup_ready.js";

    private AppHtmlInjector() {
    }

    public static String inject(String html, AndroidBridge bridge, ThemeManager theme) {
        String boot;
        try {
            JSONObject config = new JSONObject(bridge.getConfig());
            JSONObject endpoints = new JSONObject();
            String base = bridge.getEngineBaseUrl();
            endpoints.put("core", base);
            endpoints.put("core_http", base);
            endpoints.put("enginePort", bridge.getEnginePort());
            boot = "<script>(function(){"
                    + "window.__APP_CONFIG__=" + config.toString() + ";"
                    + "window.__AG_ENDPOINTS__=" + endpoints.toString() + ";"
                    + "window.__AG_CSRF__=" + JSONObject.quote(bridge.getCsrfToken()) + ";"
                    + "window.__AG_HOST_THEME__="
                    + JSONObject.quote(theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_DARK ? "dark"
                            : (theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_LIGHT ? "light" : "system")) + ";"
                    + "window.__AG_RESOLVED_THEME__="
                    + JSONObject.quote(theme != null ? theme.getTheme() : "light") + ";"
                    + "window.AndroidBridge=window.Android;"
                    + "window.__agMicCallbacks={};"
                    + "window.__agResolveMicAccess=function(id,st){var fn=window.__agMicCallbacks[id];if(fn){delete window.__agMicCallbacks[id];fn(st);}};"
                    + "window.desktopMic={"
                    + "getAccessStatus:function(){try{if(window.Android&&typeof window.Android.getMicAccessStatus==='function')return Promise.resolve(window.Android.getMicAccessStatus());}catch(e){}return Promise.resolve('granted');},"
                    + "requestAccess:function(){return new Promise(function(resolve){try{if(window.Android&&typeof window.Android.getMicAccessStatus==='function'){if(window.Android.getMicAccessStatus()==='granted'){resolve('granted');return;}var id='mic_'+Date.now()+'_'+Math.random().toString(36).slice(2);window.__agMicCallbacks[id]=resolve;window.Android.requestMicAccess(id);return;}}catch(e){}resolve('granted');});},"
                    + "openSystemPreferences:function(){try{if(window.Android&&typeof window.Android.openAppSettings==='function')window.Android.openAppSettings();}catch(e){}return Promise.resolve();}"
                    + "};"
                    + "if(navigator.permissions&&typeof navigator.permissions.query==='function'){"
                    + "var _agOrigPermQuery=navigator.permissions.query.bind(navigator.permissions);"
                    + "navigator.permissions.query=function(d){try{if(d&&d.name==='microphone'&&window.Android&&typeof window.Android.getMicAccessStatus==='function'&&window.Android.getMicAccessStatus()==='granted'){return Promise.resolve({state:'granted',onchange:null,addEventListener:function(){},removeEventListener:function(){}});}}catch(e){}return _agOrigPermQuery(d);};"
                    + "}"
                    + "})();</script>"
                    // Signals first paint back to native (AndroidBridge.onUiReady)
                    // so the splash is dismissed on real content, not a timer.
                    + "<script src=\"" + READY_SCRIPT + "\"></script>";
        } catch (Exception e) {
            Log.w(TAG, "config injection failed", e);
            return html;
        }
        int head = html.indexOf("<head>");
        if (head >= 0) {
            int at = head + "<head>".length();
            return html.substring(0, at) + "\n" + boot + html.substring(at);
        }
        return boot + html;
    }
}
