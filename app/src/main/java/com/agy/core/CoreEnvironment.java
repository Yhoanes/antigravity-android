package com.agy.core;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import com.agy.runtime.RuntimeManager;

import java.io.File;
import java.util.Map;

/** Builds the environment for the glibc core child process. */
public final class CoreEnvironment {

    private static final String TAG = "CoreEnvironment";

    private CoreEnvironment() {
    }

    public static void apply(RuntimeManager rm, ProcessBuilder pb) {
        File files = rm.appContext().getFilesDir();
        File bin = rm.getBinDir();
        Map<String, String> env = pb.environment();
        // HOME is the app files dir and never a temp dir; it is stable across
        // builds so the core always finds $HOME/.gemini (token, config, chats).
        env.put("HOME", files.getAbsolutePath());
        env.put("TMPDIR", rm.appContext().getCacheDir().getAbsolutePath());
        Log.i(TAG, "core env: HOME=" + files.getAbsolutePath()
                + " appDataDir=" + rm.getAppDataDir().getAbsolutePath()
                + " projectsDir=" + rm.getProjectsDir().getAbsolutePath()
                + " TMPDIR=" + rm.appContext().getCacheDir().getAbsolutePath());
        env.put("SSL_CERT_FILE", rm.getCertFile().getAbsolutePath());
        String inheritedPath = env.get("PATH");
        env.put("PATH", bin.getAbsolutePath()
                + (inheritedPath == null || inheritedPath.isEmpty() ? "" : ":" + inheritedPath));
        // The core's PTY spawner uses $SHELL (or falls back to /bin/bash, which
        // does not exist on Android). Point it at the bridge in runtime/bin.
        env.put("SHELL", new File(bin, "bash").getAbsolutePath());
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        // Pure-Go resolver keeps DNS independent of Android's missing /etc.
        String godebug = env.get("GODEBUG");
        env.put("GODEBUG", godebug == null || godebug.isEmpty()
                ? "netdns=go" : godebug + ",netdns=go");
        // Soft memory limit for the Go runtime GC: keeps RSS bounded on mobile
        // without dropping features or causing OOM when the heap is below the limit.
        env.put("GOGC", "50");
        env.put("GOMAXPROCS", "4");
        String goMemLimit = env.get("GOMEMLIMIT");
        if (goMemLimit == null || goMemLimit.isEmpty()) {
            goMemLimit = resolveGoMemLimit(rm.appContext());
            env.put("GOMEMLIMIT", goMemLimit);
            Log.i(TAG, "core env: GOMEMLIMIT=" + goMemLimit);
        }
        // Emits "ANTIGRAVITY_OPEN_URL: <url>" directly to stdout from language_server
        // so OpenUrlWatcher captures the auth URL and opens the external browser reliably.
        env.put("ANTIGRAVITY_VSCODE_HOST", "1");
        // Configure browser and desktop environment so core OAuth/open tools find the browser
        File xdgOpen = new File(bin, "xdg-open");
        env.put("BROWSER", xdgOpen.getAbsolutePath());
        env.put("DISPLAY", ":0");
        env.put("WAYLAND_DISPLAY", "wayland-0");
        env.put("XDG_CURRENT_DESKTOP", "X-Generic");
        env.put("XDG_DATA_DIRS", rm.getRuntimeDir().getAbsolutePath());
        env.put("XDG_DATA_HOME", new File(files, ".local/share").getAbsolutePath());
        env.put("XDG_CONFIG_HOME", new File(files, ".config").getAbsolutePath());
        // The glibc loader resolves libraries via --library-path; keep bionic's out of the way.
        env.remove("LD_LIBRARY_PATH");
        // Do NOT export LD_PRELOAD into the process environment!
        // liblse_compat.so is a GLIBC library; exporting it in env causes Bionic's
        // dynamic linker to crash when executing /system/bin/sh (xdg-open, bridges, termux).
        // RuntimeManager.coreCommand() already preloads it into language_server via the
        // glibc loader's CLI argument: --preload <path>/liblse_compat.so.
        env.remove("LD_PRELOAD");
    }

    private static String resolveGoMemLimit(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long totalMb = mi.totalMem / (1024L * 1024L);
                if (totalMb > 0) {
                    if (totalMb <= 4200) {
                        return "250MiB";
                    } else if (totalMb <= 6400) {
                        return "300MiB";
                    } else {
                        return "350MiB";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "300MiB";
    }
}
