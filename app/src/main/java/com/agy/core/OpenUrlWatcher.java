package com.agy.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.agy.oauth.OAuthManager;
import com.agy.ui.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dispatches external browser URLs and OAuth flows detected from the core's
 * stdout stream, explicit ACTION_OPEN_URL broadcasts, or the userspace
 * $HOME/.open_url_queue IPC file (used when xdg-open runs inside a PTY/subshell
 * where stdout does not go to CoreProcessSupervisor and /system/bin/am is blocked).
 */
public final class OpenUrlWatcher {

    private static final String TAG = "OpenUrlWatcher";
    public static final String QUEUE_FILE_NAME = ".open_url_queue";
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[\\w\\-\\.\\~:\\?\\#\\[\\]\\@\\!\\$\\&\\'\\(\\)\\*\\+,;=%/]+");

    private final Context appContext;
    private final OAuthManager oauth = new OAuthManager();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();
    private final File queueFile;

    private volatile String lastDispatchedUrl = "";
    private volatile long lastDispatchedTime = 0;
    private FileObserver queueObserver;
    private ScheduledExecutorService queuePoller;

    private final BroadcastReceiver openUrlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String url = intent.getStringExtra("url");
            if (url != null && !url.trim().isEmpty()) {
                Log.i(TAG, "broadcast ACTION_OPEN_URL received: " + url);
                dispatchIfValid(url);
            }
        }
    };

    public OpenUrlWatcher(Context context) {
        this.appContext = context.getApplicationContext();
        this.queueFile = new File(this.appContext.getFilesDir(), QUEUE_FILE_NAME);
    }

    @SuppressWarnings("deprecation")
    public void start() {
        try {
            if (queueFile.isFile()) {
                queueFile.delete();
            }
        } catch (Throwable ignored) {
        }
        try {
            IntentFilter filter = new IntentFilter("com.agy.ACTION_OPEN_URL");
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(openUrlReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(openUrlReceiver, filter);
            }
            Log.i(TAG, "registered ACTION_OPEN_URL receiver");
        } catch (Throwable t) {
            Log.w(TAG, "failed to register ACTION_OPEN_URL receiver: " + t.getMessage());
        }
        try {
            final int mask = FileObserver.CLOSE_WRITE | FileObserver.MODIFY
                    | FileObserver.MOVED_TO | FileObserver.CREATE;
            queueObserver = new FileObserver(appContext.getFilesDir().getAbsolutePath(), mask) {
                @Override
                public void onEvent(int event, String path) {
                    if (QUEUE_FILE_NAME.equals(path)) {
                        drainQueueFile();
                    }
                }
            };
            queueObserver.startWatching();
        } catch (Throwable t) {
            Log.w(TAG, "FileObserver setup notice: " + t.getMessage());
        }
        try {
            queuePoller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "OpenUrlQueueWatcher");
                t.setDaemon(true);
                return t;
            });
            queuePoller.scheduleWithFixedDelay(this::drainQueueFile, 600, 600, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "queuePoller setup notice: " + t.getMessage());
        }
    }

    public void stop() {
        try {
            appContext.unregisterReceiver(openUrlReceiver);
        } catch (Throwable ignored) {
        }
        try {
            if (queueObserver != null) {
                queueObserver.stopWatching();
                queueObserver = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (queuePoller != null) {
                queuePoller.shutdownNow();
                queuePoller = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void drainQueueFile() {
        if (!queueFile.isFile() || queueFile.length() == 0) {
            return;
        }
        List<String> urls = new ArrayList<>();
        synchronized (queueLock) {
            if (!queueFile.isFile() || queueFile.length() == 0) {
                return;
            }
            File snap = new File(queueFile.getParentFile(), QUEUE_FILE_NAME + ".drain");
            snap.delete();
            File readTarget = queueFile.renameTo(snap) ? snap : queueFile;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(readTarget), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        urls.add(trimmed);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                readTarget.delete();
            }
        }
        for (String u : urls) {
            dispatchIfValid(u);
        }
    }

    /** Detects stdout OPEN_URL prefixes or auth URLs; called by the stream reader. */
    public void handleStreamLine(String line) {
        if (line == null) {
            return;
        }
        for (String prefix : new String[]{"AGY_OPEN_URL=", "ANTIGRAVITY_OPEN_URL:", "ANTIGRAVITY_OPEN_URL="}) {
            int at = line.indexOf(prefix);
            if (at >= 0) {
                String url = line.substring(at + prefix.length()).trim();
                dispatchIfValid(url);
                return;
            }
        }
        // Fallback: detect OAuth or login URLs emitted by language_server / openBrowser
        if (line.contains("accounts.google.com") || line.contains("oauth2")
                || (line.contains("/auth/callback") && line.contains("http"))) {
            Matcher m = URL_PATTERN.matcher(line);
            while (m.find()) {
                String candidate = m.group();
                if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                    if (candidate.contains("accounts.google.com") || candidate.contains("oauth2")
                            || candidate.contains("auth")) {
                        Log.i(TAG, "stream regex detected auth URL: " + candidate);
                        dispatchIfValid(candidate);
                    }
                }
            }
        }
    }

    public void dispatchIfValid(String url) {
        if (url == null) return;
        url = url.trim();
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) return;

        long now = System.currentTimeMillis();
        if (url.equals(lastDispatchedUrl) && (now - lastDispatchedTime) < 1500) {
            return;
        }
        lastDispatchedUrl = url;
        lastDispatchedTime = now;
        Log.i(TAG, "dispatching external URL: " + url);
        openUrl(url);
    }

    private void openUrl(final String url) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Context target = (MainActivity.activeActivity != null)
                        ? MainActivity.activeActivity
                        : appContext;
                boolean opened = oauth.openInBrowser(target, url);
                if (!opened) {
                    Log.w(TAG, "no browser accepted the url: " + url);
                }
            }
        });
    }
}
