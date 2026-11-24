package com.agy.i18n;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Persists and resolves the user's UI language preference ("auto", "en", "es", "ar")
 * across both the Web UI and native Android overlays/notifications.
 */
public final class AppLanguageManager {

    private static final String PREFS_NAME = "agy_i18n_prefs";
    private static final String KEY_LANG_MODE = "app_language";

    private AppLanguageManager() {
    }

    public static String normalizeMode(String mode) {
        if (mode == null) {
            return "auto";
        }
        String m = mode.trim().toLowerCase(Locale.US);
        if ("en".equals(m) || "es".equals(m) || "ar".equals(m) || "auto".equals(m)) {
            return m;
        }
        return "auto";
    }

    public static String getMode(Context context) {
        if (context == null) {
            return "auto";
        }
        try {
            SharedPreferences sp = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return normalizeMode(sp.getString(KEY_LANG_MODE, "auto"));
        } catch (Throwable ignored) {
            return "auto";
        }
    }

    public static boolean setMode(Context context, String mode) {
        if (context == null) {
            return false;
        }
        String clean = normalizeMode(mode);
        try {
            context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LANG_MODE, clean)
                    .apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String resolveLanguage(Context context) {
        String mode = getMode(context);
        if (!"auto".equals(mode)) {
            return mode;
        }
        try {
            String sys = Locale.getDefault().getLanguage();
            if (sys != null) {
                String lower = sys.trim().toLowerCase(Locale.US);
                if (lower.startsWith("ar")) {
                    return "ar";
                }
                if (lower.startsWith("es")) {
                    return "es";
                }
            }
        } catch (Throwable ignored) {
        }
        return "en";
    }

    public static boolean isRtl(Context context) {
        return "ar".equals(resolveLanguage(context));
    }

    public static String tr(Context context, String en, String es, String ar) {
        String lang = resolveLanguage(context);
        if ("ar".equals(lang)) {
            return ar;
        }
        if ("es".equals(lang)) {
            return es;
        }
        return en;
    }
}
