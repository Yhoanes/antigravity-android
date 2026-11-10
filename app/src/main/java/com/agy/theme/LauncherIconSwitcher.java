package com.agy.theme;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import com.agy.ui.launcher.LauncherDarkActivity;
import com.agy.ui.launcher.LauncherLightActivity;

import com.agy.util.MainThreadPost;

/**
 * Dynamic launcher icon switcher.
 *
 * Switches launcher activity-aliases cleanly without killing the running app
 * or kicking the user out. The switch is applied while the app is in the background
 * or smoothly with DONT_KILL_APP so the user's workspace session stays uninterrupted.
 */
public final class LauncherIconSwitcher {

    private LauncherIconSwitcher() {
    }

    public static synchronized void scheduleSwitch(boolean dark) {
        // Intentionally disabled: toggling real <activity> components via PackageManager
        // causes Android ActivityManager to destroy the active Activity when backgrounded.
    }

    public static synchronized void apply(Context context, boolean dark) {
        // Intentionally disabled to keep the single launcher Activity alive across minimize/reopen.
    }

    /** Called when the app is backgrounded (onStop). */
    public static synchronized void onAppBackgrounded(Context context) {
        // No-op: prevents Activity destruction and reload when minimizing the app.
    }
}
