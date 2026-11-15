package com.agy.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import com.agy.shell.ShizukuBridge;
import com.agy.ui.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Notification bridge exposed to the Web UI and native agy-notify queue. */
public final class NotificationBridge {

    public static final String ALERTS_CHANNEL_ID = "agy_alerts_channel";
    public static final int ALERT_NOTIFICATION_ID = 2002;
    public static final String ACTIVE_SNAPSHOT_FILE = ".notify_active.json";

    private final Context context;
    private final ShizukuBridge shizuku;

    public NotificationBridge(Context context, ShizukuBridge shizuku) {
        this.context = context.getApplicationContext();
        this.shizuku = shizuku;
    }

    public boolean post(String title, String message, String tag) {
        String cleanTag = (tag != null && !tag.trim().isEmpty())
                ? tag.trim()
                : "agy_" + System.currentTimeMillis();
        if (postLocal(context, title, message, cleanTag)) {
            return true;
        }
        if (shizuku != null && shizuku.isAvailable()) {
            String safeTitle = (title != null ? title : defaultAppTitle(context)).replace("'", "'\\''");
            String safeMessage = (message != null ? message : "").replace("'", "'\\''");
            String safeTag = cleanTag.replace("'", "'\\''");
            String cmd = "cmd notification post -S bigtext -t '" + safeTitle + "' '" + safeTag + "' '" + safeMessage + "'";
            shizuku.exec(cmd);
            return true;
        }
        return false;
    }

    public String list() {
        if (shizuku != null && shizuku.isAvailable()) {
            return shizuku.exec("dumpsys notification --noredact | grep -E 'pkg=|android.title=|android.text=' | head -n 40");
        }
        return listLocalJson(context);
    }

    private static String defaultAppTitle(Context ctx) {
        try {
            CharSequence label = ctx.getApplicationInfo().loadLabel(ctx.getPackageManager());
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Throwable ignored) {
        }
        return "Antigravity";
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    ALERTS_CHANNEL_ID,
                    "Agent Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
    }

    public static boolean postLocal(Context context, String title, String message, String tag) {
        if (context == null) return false;
        try {
            Context app = context.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            ensureChannel(nm);

            String resolvedTitle = (title != null && !title.trim().isEmpty()) ? title : defaultAppTitle(app);
            String resolvedBody = message != null ? message : "";
            String resolvedTag = (tag != null && !tag.trim().isEmpty()) ? tag : "agy_" + System.currentTimeMillis();

            int iconId = app.getResources().getIdentifier("ic_notification", "drawable", app.getPackageName());
            if (iconId == 0) {
                iconId = android.R.drawable.stat_notify_chat;
            }

            Intent launchIntent = new Intent(app, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(app, resolvedTag.hashCode(), launchIntent, piFlags);

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(app, ALERTS_CHANNEL_ID)
                    : new Notification.Builder(app);
            builder.setContentTitle(resolvedTitle)
                    .setContentText(resolvedBody)
                    .setStyle(new Notification.BigTextStyle().bigText(resolvedBody))
                    .setSmallIcon(iconId)
                    .setContentIntent(pi)
                    .setAutoCancel(true);

            nm.notify(resolvedTag, ALERT_NOTIFICATION_ID, builder.build());
            syncActiveSnapshot(app);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean cancelLocal(Context context, String tag) {
        if (context == null || tag == null || tag.trim().isEmpty()) return false;
        try {
            Context app = context.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            nm.cancel(tag.trim(), ALERT_NOTIFICATION_ID);
            syncActiveSnapshot(app);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String listLocalJson(Context context) {
        if (context == null) return "[]";
        try {
            Context app = context.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return "[]";
            StatusBarNotification[] active = nm.getActiveNotifications();
            JSONArray arr = new JSONArray();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (sbn == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("id", sbn.getId());
                    item.put("tag", sbn.getTag() != null ? sbn.getTag() : "");
                    if (sbn.getNotification() != null && sbn.getNotification().extras != null) {
                        CharSequence t = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                        CharSequence tx = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
                        if (t != null) item.put("title", t.toString());
                        if (tx != null) item.put("text", tx.toString());
                    }
                    arr.put(item);
                }
            }
            return arr.toString();
        } catch (Throwable ignored) {
            return "[]";
        }
    }

    public static void syncActiveSnapshot(Context context) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            String json = listLocalJson(app);
            File snap = new File(app.getFilesDir(), ACTIVE_SNAPSHOT_FILE);
            try (FileOutputStream fos = new FileOutputStream(snap)) {
                fos.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
