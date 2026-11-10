package com.agy.shell;

import android.content.Context;
import android.util.Log;

/**
 * Rootless shell access through the bundled Shizuku `rish` bridge.
 * No Shizuku API library is linked; `rish` is executed as a child process.
 */
public final class ShizukuBridge {

    private static final String TAG = "ShizukuBridge";

    private final Context appContext;
    private final RishLocator locator;
    private final RishExecutor executor;

    public ShizukuBridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.locator = new RishLocator(context);
        this.executor = new RishExecutor(context);
    }

    public boolean isAvailable() {
        java.io.File rish = locator.rishBinary();
        return rish.exists() && rish.canExecute();
    }

    /** Runs `cmd` as shell via rish. Returns combined stdout/stderr; errors are text. */
    public String exec(String cmd) {
        return executor.exec(cmd);
    }

    /**
     * Best-effort background pass via Shizuku (`rish`) to whitelist the app from
     * Doze and disable Android 12-16's Phantom Process Killer so child toolchains
     * (compilers, node, python, subagents) are not killed with SIGKILL (signal 9).
     */
    public void applyBackgroundSurvivabilityTweaksAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isAvailable()) {
                        return;
                    }
                    String pkg = appContext.getPackageName();
                    String cmd = "dumpsys deviceidle whitelist +" + pkg + " >/dev/null 2>&1 || true; "
                            + "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true; "
                            + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true; "
                            + "device_config set_sync_disabled_for_tests persistent >/dev/null 2>&1 || true; "
                            + "device_config put activity_manager max_phantom_processes 2147483647 >/dev/null 2>&1 || true; "
                            + "settings put global settings_enable_monitor_phantom_procs false >/dev/null 2>&1 || true";
                    String res = exec(cmd);
                    if (res != null && res.startsWith("error:")) {
                        Log.d(TAG, "Shizuku survivability tweaks skipped (" + res + ")");
                    } else {
                        Log.i(TAG, "Applied Shizuku Doze whitelist & Phantom Process Killer bypass for " + pkg);
                    }
                } catch (Throwable e) {
                    Log.d(TAG, "Shizuku survivability tweaks error: " + e.getMessage());
                }
            }
        }, "ShizukuTweaksThread");
        t.setDaemon(true);
        t.start();
    }
}

