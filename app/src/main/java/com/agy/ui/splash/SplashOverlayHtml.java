package com.agy.ui.splash;

import android.content.Context;
import android.webkit.WebView;

import com.agy.i18n.AppLanguageManager;

/** Renders status/failure overlays whose Retry button calls window.SplashRetry. */
public final class SplashOverlayHtml {

    private SplashOverlayHtml() {
    }

    /** Transient status (e.g. auto-restart in progress): message, optional Retry. */
    public static void show(WebView splashView, String title, String message,
                            String foregroundColor, String backgroundColor, boolean retryable) {
        if (splashView == null) {
            return;
        }
        Context ctx = splashView.getContext();
        splashView.loadDataWithBaseURL("https://app.antigravity.local/",
                getOverlayHtml(ctx, title, message, foregroundColor, backgroundColor, retryable),
                "text/html", "utf-8", null);
    }

    public static String getOverlayHtml(Context ctx, String title, String message,
                                        String foregroundColor, String backgroundColor,
                                        boolean retryable) {
        boolean rtl = AppLanguageManager.isRtl(ctx);
        String lang = AppLanguageManager.resolveLanguage(ctx);
        String safeTitle = escape(title == null ? "Antigravity" : title);
        String safe = escape(message == null
                ? AppLanguageManager.tr(ctx, "unknown error", "error desconocido", "خطأ غير معروف")
                : message);
        String retryTxt = AppLanguageManager.tr(ctx, "Retry", "Reintentar", "إعادة المحاولة");
        String copyTxt = AppLanguageManager.tr(ctx, "Copy Logs", "Copiar registros", "نسخ السجلات");
        String saveTxt = AppLanguageManager.tr(ctx, "Save Logs", "Guardar registros", "حفظ السجلات");
        String button = retryable
                ? "  <div class=\"btn-group\">\n"
                + "    <button class=\"btn retry\" onclick=\"agyRetry()\">" + retryTxt + "</button>\n"
                + "    <button class=\"btn sec\" onclick=\"agyCopyLogs()\">" + copyTxt + "</button>\n"
                + "    <button class=\"btn sec\" onclick=\"agySaveLogs()\">" + saveTxt + "</button>\n"
                + "  </div>\n"
                : "";
        return "<!DOCTYPE html>\n"
                + "<html lang=\"" + lang + "\"" + (rtl ? " dir=\"rtl\"" : "") + ">\n"
                + "<head>\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<style>\n"
                + "  body {\n"
                + "    margin: 0;\n"
                + "    padding: 24px;\n"
                + "    box-sizing: border-box;\n"
                + "    background: " + backgroundColor + ";\n"
                + "    color: " + foregroundColor + ";\n"
                + "    font-family: system-ui, -apple-system, sans-serif;\n"
                + "    display: flex;\n"
                + "    flex-direction: column;\n"
                + "    align-items: center;\n"
                + "    justify-content: center;\n"
                + "    height: 100vh;\n"
                + "    text-align: center;\n"
                + "  }\n"
                + "  .title { font-size: 15px; font-weight: 600; margin-bottom: 10px; }\n"
                + "  .msg {\n"
                + "    font-size: 12px;\n"
                + "    opacity: 0.75;\n"
                + "    word-break: break-word;\n"
                + "    max-width: 90vw;\n"
                + "    white-space: pre-wrap;\n"
                + "  }\n"
                + "  .btn-group {\n"
                + "    display: flex;\n"
                + "    flex-wrap: wrap;\n"
                + "    gap: 10px;\n"
                + "    justify-content: center;\n"
                + "    align-items: center;\n"
                + "    margin-top: 20px;\n"
                + "  }\n"
                + "  .btn {\n"
                + "    padding: 9px 18px;\n"
                + "    font-size: 13px;\n"
                + "    font-weight: 500;\n"
                + "    border-radius: 6px;\n"
                + "    cursor: pointer;\n"
                + "    box-sizing: border-box;\n"
                + "    font-family: inherit;\n"
                + "  }\n"
                + "  .retry {\n"
                + "    color: " + backgroundColor + ";\n"
                + "    background: " + foregroundColor + ";\n"
                + "    border: none;\n"
                + "  }\n"
                + "  .sec {\n"
                + "    color: " + foregroundColor + ";\n"
                + "    background: transparent;\n"
                + "    border: 1px solid " + foregroundColor + ";\n"
                + "    opacity: 0.85;\n"
                + "  }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div class=\"title\">" + safeTitle + "</div>\n"
                + "  <div class=\"msg\">" + safe + "</div>\n"
                + button
                + "  <script>\n"
                + "    function agyRetry() {\n"
                + "      if (window.SplashRetry && window.SplashRetry.retry) {\n"
                + "        window.SplashRetry.retry();\n"
                + "      }\n"
                + "    }\n"
                + "    function agyCopyLogs() {\n"
                + "      if (window.SplashRetry && window.SplashRetry.copyLogs) {\n"
                + "        window.SplashRetry.copyLogs();\n"
                + "      }\n"
                + "    }\n"
                + "    function agySaveLogs() {\n"
                + "      if (window.SplashRetry && window.SplashRetry.saveLogs) {\n"
                + "        window.SplashRetry.saveLogs();\n"
                + "      }\n"
                + "    }\n"
                + "  </script>\n"
                + "</body>\n"
                + "</html>\n";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
