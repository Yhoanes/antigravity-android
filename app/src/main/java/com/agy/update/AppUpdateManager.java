package com.agy.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;

import com.agy.shell.RishLocator;
import com.agy.shell.ShizukuBridge;
import com.agy.ui.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Native in-app auto-updater backing window.electronUpdater on Android.
 * Checks GitHub Releases (Yhoanes/antigravity-android), downloads newer APKs
 * in the background, exposes "Restart to Update", and installs silently via
 * Shizuku (pm install -r -d) or via Android's standard PackageInstaller.
 */
public final class AppUpdateManager {

    private static final String TAG = "AppUpdateManager";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_CHECKING = "checking for updates";
    public static final String STATE_AVAILABLE = "available for download";
    public static final String STATE_DOWNLOADING = "downloading";
    public static final String STATE_READY = "ready";

    private static final String RELEASES_LATEST_URL =
            "https://api.github.com/repos/Yhoanes/antigravity-android/releases/latest";
    private static final String USER_AGENT = "Antigravity-Android-Updater/2.31.1";
    private static final long MIN_AUTO_CHECK_GAP_MS = 45_000L;

    private static volatile AppUpdateManager instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile String stateType = STATE_IDLE;
    private volatile String targetVersion = null;
    private volatile boolean checkInFlight = false;
    private volatile long lastCheckStartedMs = 0L;
    private volatile boolean initialAutoCheckScheduled = false;
    private volatile boolean pendingInstallAfterPermission = false;
    private volatile WebView activeWebView;

    private AppUpdateManager(Context context) {
        this.appContext = context.getApplicationContext();
        cleanupStaleStagedApk();
    }

    public static AppUpdateManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AppUpdateManager.class) {
                if (instance == null) {
                    instance = new AppUpdateManager(context);
                }
            }
        }
        return instance;
    }

    public static File getOtaDir(Context context) {
        File dir = new File(context.getCacheDir(), "ota_updates");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getStagedApkFile(Context context) {
        return new File(getOtaDir(context), "update.apk");
    }

    private static File getStagedMetaFile(Context context) {
        return new File(getOtaDir(context), "update.json");
    }

    public void attachWebView(WebView webView) {
        this.activeWebView = webView;
        if (!initialAutoCheckScheduled) {
            initialAutoCheckScheduled = true;
            mainHandler.postDelayed(() -> checkForUpdates(false), 10_000L);
        }
    }

    public void onActivityResumed(Activity activity, WebView webView) {
        if (webView != null) {
            attachWebView(webView);
        }
        if (pendingInstallAfterPermission) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || appContext.getPackageManager().canRequestPackageInstalls()) {
                pendingInstallAfterPermission = false;
                mainHandler.postDelayed(() -> launchSystemPackageInstaller(activity), 350L);
            }
        }
    }

    public String getStateJson() {
        try {
            JSONObject obj = new JSONObject();
            String st = this.stateType;
            String ver = this.targetVersion;
            obj.put("type", st);
            if (ver != null && !ver.isEmpty()
                    && (STATE_AVAILABLE.equals(st) || STATE_DOWNLOADING.equals(st) || STATE_READY.equals(st))) {
                JSONObject upd = new JSONObject();
                upd.put("version", ver);
                obj.put("update", upd);
            }
            return obj.toString();
        } catch (Throwable t) {
            return "{\"type\":\"idle\"}";
        }
    }

    private void updateState(String newType, String version) {
        this.stateType = newType;
        this.targetVersion = version;
        final String json = getStateJson();
        mainHandler.post(() -> {
            WebView wv = activeWebView;
            if (wv == null && MainActivity.activeActivity != null) {
                // Best-effort notify if WebView reference was refreshed
            }
            if (wv != null) {
                try {
                    wv.evaluateJavascript(
                            "window.__agOnUpdaterStateChanged&&window.__agOnUpdaterStateChanged("
                                    + json + ");",
                            null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void showToast(final String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        mainHandler.post(() -> {
            try {
                Context ctx = MainActivity.activeActivity != null ? MainActivity.activeActivity : appContext;
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    public String getInstalledVersion() {
        try {
            PackageInfo pi = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            if (pi != null && pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                return normalizeVersion(pi.versionName);
            }
        } catch (Throwable ignored) {
        }
        return "2.31.1";
    }

    public static String normalizeVersion(String raw) {
        if (raw == null) {
            return "0.0.0";
        }
        String s = raw.trim();
        while (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1).trim();
        }
        return s.isEmpty() ? "0.0.0" : s;
    }

    private static int[] parseVersionParts(String raw) {
        String v = normalizeVersion(raw);
        int plusIdx = v.indexOf('+');
        if (plusIdx >= 0) {
            v = v.substring(0, plusIdx);
        }
        int revNum = 0;
        int dashIdx = v.indexOf('-');
        if (dashIdx >= 0) {
            String suffix = v.substring(dashIdx + 1).trim().toLowerCase();
            v = v.substring(0, dashIdx);
            if (suffix.startsWith("r")) {
                suffix = suffix.substring(1);
            }
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < suffix.length(); i++) {
                char c = suffix.charAt(i);
                if (c >= '0' && c <= '9') {
                    digits.append(c);
                } else if (digits.length() > 0) {
                    break;
                }
            }
            if (digits.length() > 0) {
                try {
                    revNum = Integer.parseInt(digits.toString());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String[] segs = v.split("\\.");
        int[] out = new int[5];
        for (int i = 0; i < Math.min(segs.length, 4); i++) {
            try {
                out[i] = Integer.parseInt(segs[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        out[4] = revNum;
        return out;
    }

    public static boolean isRemoteNewer(String installed, String remote) {
        int[] a = parseVersionParts(installed);
        int[] b = parseVersionParts(remote);
        for (int i = 0; i < a.length; i++) {
            if (b[i] != a[i]) {
                return b[i] > a[i];
            }
        }
        return false;
    }

    private void cleanupStaleStagedApk() {
        try {
            File apk = getStagedApkFile(appContext);
            File meta = getStagedMetaFile(appContext);
            if (!apk.exists() && !meta.exists()) {
                return;
            }
            String installed = getInstalledVersion();
            if (meta.isFile()) {
                String raw = readSmallFile(meta);
                JSONObject obj = new JSONObject(raw);
                String stagedVer = obj.optString("version", "");
                long expectedSize = obj.optLong("size", -1L);
                if (!stagedVer.isEmpty() && isRemoteNewer(installed, stagedVer)
                        && apk.isFile() && (expectedSize <= 0L || apk.length() == expectedSize)
                        && isValidApkArchive(apk)) {
                    this.stateType = STATE_READY;
                    this.targetVersion = stagedVer;
                    Log.i(TAG, "Restored verified staged update v" + stagedVer);
                    return;
                }
            }
            if (apk.exists()) apk.delete();
            if (meta.exists()) meta.delete();
        } catch (Throwable t) {
            Log.w(TAG, "cleanupStaleStagedApk: " + t.getMessage());
        }
    }

    public void checkForUpdates(final boolean manual) {
        long now = System.currentTimeMillis();
        if (checkInFlight) {
            if (manual) {
                showToast("Checking for updates…");
            }
            return;
        }
        if (STATE_READY.equals(stateType) && targetVersion != null && getStagedApkFile(appContext).isFile()) {
            if (manual) {
                showToast("Update v" + targetVersion + " is ready — tap Restart to Update");
            }
            updateState(STATE_READY, targetVersion);
            return;
        }
        if (!manual && (now - lastCheckStartedMs) < MIN_AUTO_CHECK_GAP_MS) {
            return;
        }
        checkInFlight = true;
        lastCheckStartedMs = now;

        Thread worker = new Thread(() -> {
            try {
                runUpdateCheck(manual);
            } finally {
                checkInFlight = false;
            }
        }, "AgyAppUpdater");
        worker.setDaemon(true);
        worker.start();
    }

    private void runUpdateCheck(boolean manual) {
        updateState(STATE_CHECKING, null);
        try {
            JSONObject release = fetchJsonFollowRedirects(RELEASES_LATEST_URL);
            String tagName = release.optString("tag_name", "").trim();
            if (tagName.isEmpty()) {
                updateState(STATE_IDLE, null);
                if (manual) {
                    showToast("Could not read release version");
                }
                return;
            }
            String remoteVer = normalizeVersion(tagName);
            String installedVer = getInstalledVersion();
            if (!isRemoteNewer(installedVer, remoteVer)) {
                updateState(STATE_IDLE, null);
                if (manual) {
                    showToast("Antigravity is up to date (v" + installedVer + ")");
                }
                return;
            }

            JSONArray assets = release.optJSONArray("assets");
            JSONObject chosenAsset = selectApkAsset(assets);
            if (chosenAsset == null) {
                updateState(STATE_IDLE, null);
                if (manual) {
                    showToast("Release v" + remoteVer + " has no APK asset yet");
                }
                return;
            }

            String downloadUrl = chosenAsset.optString("browser_download_url", "");
            long expectedSize = chosenAsset.optLong("size", -1L);
            if (downloadUrl.isEmpty()) {
                updateState(STATE_IDLE, null);
                return;
            }

            File stagedApk = getStagedApkFile(appContext);
            File stagedMeta = getStagedMetaFile(appContext);
            if (stagedApk.isFile() && (expectedSize <= 0L || stagedApk.length() == expectedSize)
                    && isValidApkArchive(stagedApk)) {
                PackageInfo archiveInfo = appContext.getPackageManager()
                        .getPackageArchiveInfo(stagedApk.getAbsolutePath(), 0);
                if (archiveInfo != null && remoteVer.equals(normalizeVersion(archiveInfo.versionName))) {
                    writeStagedMeta(stagedMeta, remoteVer, stagedApk.length());
                    updateState(STATE_READY, remoteVer);
                    if (manual) {
                        showToast("Update v" + remoteVer + " ready to install");
                    }
                    return;
                }
            }

            updateState(STATE_AVAILABLE, remoteVer);
            updateState(STATE_DOWNLOADING, remoteVer);

            File partFile = new File(getOtaDir(appContext), "update.apk.part");
            if (partFile.exists()) {
                partFile.delete();
            }

            downloadBinaryFollowRedirects(downloadUrl, partFile, expectedSize);

            if (!isValidApkArchive(partFile)) {
                partFile.delete();
                throw new IllegalStateException("Downloaded APK failed archive verification");
            }

            if (stagedApk.exists() && !stagedApk.delete()) {
                throw new IllegalStateException("Cannot replace previous staged APK");
            }
            if (!partFile.renameTo(stagedApk)) {
                copyFile(partFile, stagedApk);
                partFile.delete();
            }

            writeStagedMeta(stagedMeta, remoteVer, stagedApk.length());
            Log.i(TAG, "Update v" + remoteVer + " downloaded (" + stagedApk.length() + " bytes)");
            updateState(STATE_READY, remoteVer);
            if (manual) {
                showToast("Update v" + remoteVer + " downloaded — tap Restart to Update");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Update check failed: " + t.getMessage());
            updateState(STATE_IDLE, null);
            if (manual) {
                showToast("Update check failed: " + t.getMessage());
            }
        }
    }

    private static JSONObject selectApkAsset(JSONArray assets) {
        if (assets == null || assets.length() == 0) {
            return null;
        }
        JSONObject fallbackApk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            String lower = name.toLowerCase();
            if (lower.endsWith(".apk")) {
                if (lower.startsWith("antigravity")) {
                    return a;
                }
                if (fallbackApk == null) {
                    fallbackApk = a;
                }
            }
        }
        return fallbackApk;
    }

    private static JSONObject fetchJsonFollowRedirects(String urlStr) throws Exception {
        String currentUrl = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        throw new IllegalStateException("Redirect missing Location");
                    }
                    currentUrl = new URL(new URL(currentUrl), loc).toString();
                    continue;
                }
                if (code != 200) {
                    throw new IllegalStateException("GitHub API HTTP " + code);
                }
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new IllegalStateException("Too many redirects");
    }

    private static void downloadBinaryFollowRedirects(String urlStr, File destFile, long expectedSize) throws Exception {
        String currentUrl = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(60_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/octet-stream, */*");
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        throw new IllegalStateException("Asset redirect missing Location");
                    }
                    currentUrl = new URL(new URL(currentUrl), loc).toString();
                    continue;
                }
                if (code != 200) {
                    throw new IllegalStateException("Download HTTP " + code);
                }
                long total = 0L;
                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 65536);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(destFile), 65536)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    out.flush();
                }
                if (expectedSize > 0L && total != expectedSize) {
                    destFile.delete();
                    throw new IllegalStateException("Incomplete APK download (" + total + "/" + expectedSize + " bytes)");
                }
                return;
            } finally {
                conn.disconnect();
            }
        }
        throw new IllegalStateException("Too many download redirects");
    }

    private boolean isValidApkArchive(File apkFile) {
        if (apkFile == null || !apkFile.isFile() || apkFile.length() < 1024L) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(apkFile)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) != 4
                    || magic[0] != 0x50 || magic[1] != 0x4B || magic[2] != 0x03 || magic[3] != 0x04) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        try {
            PackageInfo pi = appContext.getPackageManager()
                    .getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            return pi != null && appContext.getPackageName().equals(pi.packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    public void applyUpdate() {
        final File apk = getStagedApkFile(appContext);
        if (!apk.isFile() || !isValidApkArchive(apk)) {
            checkForUpdates(true);
            return;
        }
        Thread installerThread = new Thread(() -> {
            if (trySilentInstallViaShizuku(apk)) {
                return;
            }
            mainHandler.post(() -> launchSystemPackageInstaller(MainActivity.activeActivity));
        }, "AgyApkInstaller");
        installerThread.setDaemon(true);
        installerThread.start();
    }

    private boolean trySilentInstallViaShizuku(File apkFile) {
        try {
            ShizukuBridge shizuku = new ShizukuBridge(appContext);
            if (!shizuku.isAvailable()) {
                return false;
            }
            String idCheck = shizuku.exec("id");
            if (idCheck == null || (!idCheck.contains("uid=2000") && !idCheck.contains("uid=0"))) {
                return false;
            }

            File rish = new RishLocator(appContext).rishBinary();
            if (!rish.isFile() || !rish.canExecute()) {
                return false;
            }

            String pkg = appContext.getPackageName();
            String remoteTmp = "/data/local/tmp/agy_ota_" + pkg.replace('.', '_') + ".apk";

            showToast("Installing update via Shizuku…");

            ProcessBuilder pb = new ProcessBuilder(
                    rish.getAbsolutePath(),
                    "-c",
                    "env -u LD_LIBRARY_PATH sh -c 'rm -f " + remoteTmp
                            + " && cat > " + remoteTmp
                            + " && chmod 644 " + remoteTmp
                            + " && wc -c < " + remoteTmp + "'");
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.remove("LD_LIBRARY_PATH");
            env.put("RISH_APPLICATION_ID", pkg);
            pb.directory(appContext.getFilesDir());

            Process p = pb.start();
            try (InputStream fis = new BufferedInputStream(new FileInputStream(apkFile), 65536);
                 OutputStream pos = new BufferedOutputStream(p.getOutputStream(), 65536)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    pos.write(buf, 0, n);
                }
                pos.flush();
            }

            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line.trim());
                }
            }
            int code = p.waitFor();
            if (code != 0) {
                Log.w(TAG, "Shizuku stream APK exited " + code + ": " + out);
                return false;
            }

            long remoteBytes = -1L;
            try {
                remoteBytes = Long.parseLong(out.toString().replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
            if (remoteBytes != apkFile.length()) {
                shizuku.exec("rm -f " + remoteTmp);
                Log.w(TAG, "Shizuku size mismatch: " + remoteBytes + " != " + apkFile.length());
                return false;
            }

            String installCmd = "nohup sh -c 'sleep 0.6; pm install -r -d " + remoteTmp
                    + " >/dev/null 2>&1; rm -f " + remoteTmp
                    + "; monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1'"
                    + " </dev/null >/dev/null 2>&1 &";
            String res = shizuku.exec(installCmd);
            if (res != null && res.startsWith("error:")) {
                shizuku.exec("rm -f " + remoteTmp);
                return false;
            }
            Log.i(TAG, "Detached Shizuku pm install scheduled for " + remoteTmp);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku install fallback: " + t.getMessage());
            return false;
        }
    }

    private void launchSystemPackageInstaller(Activity activity) {
        try {
            Context host = activity != null ? activity : appContext;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !appContext.getPackageManager().canRequestPackageInstalls()) {
                pendingInstallAfterPermission = true;
                showToast("Allow 'Install unknown apps' to apply update");
                Intent permIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + appContext.getPackageName()));
                if (!(host instanceof Activity)) {
                    permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                host.startActivity(permIntent);
                return;
            }

            pendingInstallAfterPermission = false;
            Uri apkUri = UpdateApkProvider.buildUpdateUri(appContext);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            host.startActivity(installIntent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch system package installer: " + t.getMessage());
            showToast("Cannot launch installer: " + t.getMessage());
        }
    }

    private static void writeStagedMeta(File metaFile, String version, long size) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("version", version);
            obj.put("size", size);
            obj.put("savedAt", System.currentTimeMillis());
            try (FileOutputStream fos = new FileOutputStream(metaFile)) {
                fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readSmallFile(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src), 65536);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 65536)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }
}
