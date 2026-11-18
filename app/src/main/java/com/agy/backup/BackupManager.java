package com.agy.backup;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;

import com.agy.bridge.EngineBridge;
import com.agy.core.CoreReadyProbe;
import com.agy.core.CoreRuntimeState;
import com.agy.core.CoreServiceLauncher;
import com.agy.runtime.RuntimeManager;
import com.agy.ui.MainActivity;
import com.agy.update.AppUpdateManager;
import com.agy.util.PathSafety;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Native Backup & Restore engine for Antigravity user data:
 *   - Conversations & Artifacts (.gemini/antigravity-app/conversations, summaries, brain)
 *   - Skills, Rules, Agents & Plugins (.gemini/config/{skills,agents,sidecars,plugins,AGENTS.md})
 *   - Projects & Settings (.gemini/config/{projects,config.json,mcp_config.json}, ~/workspace)
 *
 * Security invariants:
 *   1. OAuth tokens, .netrc, signing keys, SSH keys, and ADB keys are strictly excluded on both
 *      export and restore so portable archives never leak account sessions or private keys.
 *   2. Restore runs a full pre-flight archive validation (manifest check, canonical Zip-Slip guard,
 *      symlink-parent guard, entry/byte quotas, and free-space check) BEFORE stopping CoreServer.
 *   3. CoreServerService is cleanly stopped before SQLite extraction and restarted immediately after.
 */
public final class BackupManager {

    private static final String TAG = "BackupManager";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_EXPORTING = "exporting";
    public static final String STATE_RESTORING = "restoring";
    public static final String STATE_SUCCESS = "success";
    public static final String STATE_ERROR = "error";

    private static final String MANIFEST_ENTRY = "agy-backup-manifest.json";
    private static final String BACKUP_MAGIC = "antigravity-android-backup";
    private static final int SCHEMA_VERSION = 1;

    private static final int MAX_MANIFEST_BYTES = 512 * 1024;
    private static final int MAX_RESTORE_ENTRIES = 50_000;
    private static final long MAX_RESTORE_BYTES = 4L * 1024L * 1024L * 1024L; // 4 GiB
    private static final long MIN_FREE_HEADROOM_BYTES = 64L * 1024L * 1024L;  // 64 MiB

    private static volatile BackupManager instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean statsBusy = new AtomicBoolean(false);

    private volatile WeakReference<WebView> activeWebViewRef = new WeakReference<>(null);
    private volatile String stateType = STATE_IDLE;
    private volatile String stateMessage = "";
    private volatile int statePercent = 0;

    private volatile boolean includeConversations = true;
    private volatile boolean includeSkillsPlugins = true;
    private volatile boolean includeProjects = true;

    private volatile boolean statsReady = false;
    private volatile long lastStatsMs = 0L;
    private volatile long convBytes = 0L;
    private volatile int convFiles = 0;
    private volatile long skillsBytes = 0L;
    private volatile int skillsFiles = 0;
    private volatile long projBytes = 0L;
    private volatile int projFiles = 0;

    private BackupManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static BackupManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BackupManager.class) {
                if (instance == null) {
                    instance = new BackupManager(context);
                }
            }
        }
        return instance;
    }

    public void attachWebView(WebView webView) {
        this.activeWebViewRef = new WeakReference<>(webView);
        refreshStatsAsync(false);
    }

    public void detachWebView(WebView webView) {
        WebView cur = this.activeWebViewRef.get();
        if (cur == null || cur == webView) {
            this.activeWebViewRef = new WeakReference<>(null);
        }
    }

    public void setOptions(boolean conversations, boolean skillsPlugins, boolean projects) {
        this.includeConversations = conversations;
        this.includeSkillsPlugins = skillsPlugins;
        this.includeProjects = projects;
        if (!busy.get() && (STATE_SUCCESS.equals(stateType) || STATE_ERROR.equals(stateType))) {
            this.stateType = STATE_IDLE;
            this.stateMessage = "";
            this.statePercent = 0;
        }
        notifyWebView();
    }

    public void refreshStatsAsync(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && statsReady && (now - lastStatsMs) < 15_000L) {
            notifyWebView();
            return;
        }
        if (!statsBusy.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                File filesDir = appContext.getFilesDir();
                List<ExportItem> cList = collectExportItems(filesDir, true, false, false);
                List<ExportItem> sList = collectExportItems(filesDir, false, true, false);
                List<ExportItem> pList = collectExportItems(filesDir, false, false, true);

                long cSum = 0L;
                for (ExportItem it : cList) cSum += Math.max(0L, it.file.length());
                long sSum = 0L;
                for (ExportItem it : sList) sSum += Math.max(0L, it.file.length());
                long pSum = 0L;
                for (ExportItem it : pList) pSum += Math.max(0L, it.file.length());

                this.convBytes = cSum;
                this.convFiles = cList.size();
                this.skillsBytes = sSum;
                this.skillsFiles = sList.size();
                this.projBytes = pSum;
                this.projFiles = pList.size();
                this.statsReady = true;
                this.lastStatsMs = System.currentTimeMillis();
                notifyWebView();
            } catch (Throwable ignored) {
            } finally {
                statsBusy.set(false);
            }
        }, "AgyBackupStats");
        t.setDaemon(true);
        t.start();
    }

    public String getStateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", stateType);
            o.put("message", stateMessage != null ? stateMessage : "");
            o.put("percent", statePercent);
            o.put("conversations", includeConversations);
            o.put("skillsPlugins", includeSkillsPlugins);
            o.put("projects", includeProjects);
            o.put("statsReady", statsReady);
            o.put("convBytes", convBytes);
            o.put("convFiles", convFiles);
            o.put("skillsBytes", skillsBytes);
            o.put("skillsFiles", skillsFiles);
            o.put("projBytes", projBytes);
            o.put("projFiles", projFiles);
            return o.toString();
        } catch (Exception e) {
            return "{\"type\":\"idle\",\"message\":\"\",\"percent\":0,"
                    + "\"conversations\":true,\"skillsPlugins\":true,\"projects\":true}";
        }
    }

    private void updateState(String type, String message, int percent) {
        this.stateType = type;
        this.stateMessage = message;
        this.statePercent = Math.max(0, Math.min(100, percent));
        notifyWebView();
    }

    private void notifyWebView() {
        final String json = getStateJson();
        mainHandler.post(() -> {
            try {
                WebView wv = activeWebViewRef.get();
                if (wv == null && MainActivity.activeActivity != null) {
                    wv = MainActivity.activeActivity.getWebView();
                }
                if (wv != null) {
                    wv.evaluateJavascript(
                            "window.__agOnBackupStateChanged&&window.__agOnBackupStateChanged("
                                    + json + ");",
                            null);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private void showToast(final String msg) {
        mainHandler.post(() -> {
            try {
                Context ctx = MainActivity.activeActivity != null ? MainActivity.activeActivity : appContext;
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    public static String buildSuggestedBackupFilename() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US);
        return "antigravity-backup-" + sdf.format(new Date()) + ".zip";
    }

    public void requestExport(final MainActivity activity,
                              boolean conversations,
                              boolean skillsPlugins,
                              boolean projects) {
        this.includeConversations = conversations;
        this.includeSkillsPlugins = skillsPlugins;
        this.includeProjects = projects;

        if (busy.get()) {
            showToast("A backup or restore operation is already in progress.");
            return;
        }
        if (!conversations && !skillsPlugins && !projects) {
            updateState(STATE_ERROR, "Select at least one category to export.", 0);
            showToast("Select at least one category to export.");
            return;
        }
        if (activity == null) {
            updateState(STATE_ERROR, "Cannot open file picker (activity unavailable).", 0);
            return;
        }
        final String suggested = buildSuggestedBackupFilename();
        mainHandler.post(() -> {
            if (!activity.launchBackupExportPicker(suggested)) {
                exportFallbackToDownloads(suggested);
            }
        });
    }

    private void exportFallbackToDownloads(final String filename) {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloads.exists()) {
                downloads.mkdirs();
            }
            File outFile = new File(downloads, filename);
            startExportToFileOrUri(null, outFile);
        } catch (Throwable t) {
            updateState(STATE_ERROR, "Cannot create backup file: " + safeMsg(t), 0);
        }
    }

    public void startExportToUri(final Uri uri) {
        if (uri == null) {
            return;
        }
        startExportToFileOrUri(uri, null);
    }

    private void startExportToFileOrUri(final Uri targetUri, final File fallbackFile) {
        if (!busy.compareAndSet(false, true)) {
            showToast("Backup already in progress.");
            return;
        }
        final boolean doConv = this.includeConversations;
        final boolean doSkills = this.includeSkillsPlugins;
        final boolean doProj = this.includeProjects;

        updateState(STATE_EXPORTING, "Scanning files to export\u2026", 2);

        Thread worker = new Thread(() -> {
            long writtenBytes = 0L;
            try {
                File filesDir = appContext.getFilesDir();
                List<ExportItem> items = collectExportItems(filesDir, doConv, doSkills, doProj);
                if (items.isEmpty()) {
                    updateState(STATE_ERROR, "No matching data found to export yet.", 0);
                    showToast("No matching data found to export.");
                    return;
                }

                long totalBytes = 0L;
                JSONArray execArr = new JSONArray();
                for (ExportItem it : items) {
                    totalBytes += Math.max(0L, it.file.length());
                    if (it.executable) {
                        execArr.put(it.relPath);
                    }
                }

                JSONObject scopes = new JSONObject();
                scopes.put("conversations", doConv);
                scopes.put("skillsPlugins", doSkills);
                scopes.put("projects", doProj);

                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                iso.setTimeZone(TimeZone.getTimeZone("UTC"));

                JSONObject manifest = new JSONObject();
                manifest.put("magic", BACKUP_MAGIC);
                manifest.put("schemaVersion", SCHEMA_VERSION);
                manifest.put("appVersion", AppUpdateManager.getInstance(appContext).getInstalledVersion());
                manifest.put("createdAt", iso.format(new Date()));
                manifest.put("fileCount", items.size());
                manifest.put("uncompressedBytes", totalBytes);
                manifest.put("scopes", scopes);
                manifest.put("execPaths", execArr);

                byte[] manifestBytes = manifest.toString(2).getBytes(StandardCharsets.UTF_8);

                CountingOutputStream countingOut = null;
                try (OutputStream rawOut = (targetUri != null)
                        ? appContext.getContentResolver().openOutputStream(targetUri)
                        : new FileOutputStream(fallbackFile)) {
                    if (rawOut == null) {
                        throw new IOException("Cannot open destination output stream");
                    }
                    countingOut = new CountingOutputStream(new BufferedOutputStream(rawOut, 65536));
                    try (ZipOutputStream zos = new ZipOutputStream(countingOut)) {
                        zos.setLevel(Deflater.BEST_SPEED);

                        ZipEntry mfEntry = new ZipEntry(MANIFEST_ENTRY);
                        zos.putNextEntry(mfEntry);
                        zos.write(manifestBytes);
                        zos.closeEntry();

                        byte[] buf = new byte[65536];
                        long processedBytes = 0L;
                        int lastPct = 5;
                        updateState(STATE_EXPORTING, "Exporting backup\u2026 5%", 5);

                        for (int i = 0; i < items.size(); i++) {
                            ExportItem it = items.get(i);
                            if (!it.file.isFile() || isSymlink(it.file)) {
                                continue;
                            }
                            final InputStream fis;
                            try {
                                fis = new BufferedInputStream(new FileInputStream(it.file), 65536);
                            } catch (IOException vanished) {
                                // Transient WAL or temp file checkpointed/removed mid-export: skip cleanly
                                continue;
                            }
                            try {
                                ZipEntry ze = new ZipEntry(it.relPath);
                                ze.setTime(it.file.lastModified());
                                zos.putNextEntry(ze);
                                int r;
                                while ((r = fis.read(buf)) > 0) {
                                    zos.write(buf, 0, r);
                                    processedBytes += r;
                                    int pct = totalBytes > 0
                                            ? (int) Math.min(99L, 5L + (processedBytes * 94L) / totalBytes)
                                            : (5 + ((i + 1) * 94) / items.size());
                                    if (pct >= lastPct + 3) {
                                        lastPct = pct;
                                        updateState(STATE_EXPORTING, "Exporting backup\u2026 " + pct + "%", pct);
                                    }
                                }
                                zos.closeEntry();
                            } finally {
                                try {
                                    fis.close();
                                } catch (IOException ignored) {
                                }
                            }
                        }
                        zos.finish();
                    }
                    countingOut.flush();
                    writtenBytes = countingOut.count;
                }

                String summary = "Backup saved (" + formatBytes(writtenBytes) + " zip, " + items.size() + " files).";
                updateState(STATE_SUCCESS, summary, 100);
                showToast(summary);
                refreshStatsAsync(true);
            } catch (Throwable t) {
                Log.e(TAG, "Backup export failed", t);
                // Clean up partial/corrupted output file so user never keeps a truncated archive
                if (fallbackFile != null && fallbackFile.exists()) {
                    try {
                        fallbackFile.delete();
                    } catch (Throwable ignored) {
                    }
                } else if (targetUri != null) {
                    try {
                        android.provider.DocumentsContract.deleteDocument(
                                appContext.getContentResolver(), targetUri);
                    } catch (Throwable ignored) {
                    }
                }
                String err = "Export failed: " + safeMsg(t);
                updateState(STATE_ERROR, err, 0);
                showToast(err);
            } finally {
                busy.set(false);
            }
        }, "AgyBackupExport");
        worker.setDaemon(true);
        worker.start();
    }

    public void requestRestore(final MainActivity activity) {
        if (busy.get()) {
            showToast("A backup or restore operation is already in progress.");
            return;
        }
        if (activity == null) {
            updateState(STATE_ERROR, "Cannot open file picker (activity unavailable).", 0);
            return;
        }
        mainHandler.post(activity::launchBackupRestorePicker);
    }

    public void promptRestoreConfirmation(final MainActivity activity, final Uri uri) {
        if (uri == null || activity == null) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            showToast("Another backup operation is currently running.");
            return;
        }
        updateState(STATE_RESTORING, "Verifying backup archive\u2026", 5);

        Thread verifier = new Thread(() -> {
            try {
                final ValidatedBackup validated = preflightValidateArchive(uri);
                busy.set(false);
                updateState(STATE_IDLE, "", 0);

                mainHandler.post(() -> {
                    MainActivity targetAct = MainActivity.activeActivity != null
                            ? MainActivity.activeActivity : activity;
                    if (targetAct == null || targetAct.isFinishing() || targetAct.isDestroyed()) {
                        return;
                    }
                    StringBuilder msg = new StringBuilder();
                    msg.append("Created: ").append(validated.createdAt);
                    if (!validated.appVersion.isEmpty()) {
                        msg.append(" (v").append(validated.appVersion).append(")");
                    }
                    msg.append("\nContents: ").append(validated.describeScopes());
                    msg.append("\nFiles: ").append(validated.entryCount)
                            .append(" (").append(formatBytes(validated.uncompressedBytes)).append(")");
                    msg.append("\n\nThe local engine will pause for a few seconds to safely update SQLite databases and reload. Your current Google sign-in and private keys will remain intact.");

                    new AlertDialog.Builder(targetAct)
                            .setTitle("Restore Antigravity Backup?")
                            .setMessage(msg.toString())
                            .setNegativeButton("Cancel", (d, w) -> {
                                updateState(STATE_IDLE, "", 0);
                            })
                            .setOnCancelListener(d -> {
                                updateState(STATE_IDLE, "", 0);
                            })
                            .setPositiveButton("Restore & Reload", (d, w) -> {
                                MainActivity liveAct = MainActivity.activeActivity != null
                                        ? MainActivity.activeActivity : targetAct;
                                executeValidatedRestore(liveAct, uri, validated);
                            })
                            .setCancelable(true)
                            .show();
                });
            } catch (Throwable t) {
                busy.set(false);
                Log.w(TAG, "Backup verification failed", t);
                String err = "Invalid backup file: " + safeMsg(t);
                updateState(STATE_ERROR, err, 0);
                showToast(err);
            }
        }, "AgyBackupVerify");
        verifier.setDaemon(true);
        verifier.start();
    }

    private void executeValidatedRestore(final MainActivity activity,
                                         final Uri uri,
                                         final ValidatedBackup validated) {
        if (!busy.compareAndSet(false, true)) {
            showToast("Backup operation already running.");
            return;
        }

        CoreRuntimeState.serverUrl = null;
        updateState(STATE_RESTORING, "Stopping local engine safely\u2026", 10);
        mainHandler.post(() -> {
            MainActivity liveAct = MainActivity.activeActivity != null ? MainActivity.activeActivity : activity;
            if (liveAct != null && !liveAct.isFinishing() && !liveAct.isDestroyed()) {
                liveAct.prepareForCoreRestart();
            }
        });

        Thread restorer = new Thread(() -> {
            boolean serviceStopped = false;
            try {
                File filesDir = appContext.getFilesDir();

                // 1. Cleanly stop CoreServerService so language_server closes SQLite WAL descriptors
                int port = CoreRuntimeState.httpsPort > 0 ? CoreRuntimeState.httpsPort : 45157;
                CoreServiceLauncher.stop(appContext);
                serviceStopped = true;
                long stopDeadline = System.currentTimeMillis() + 5000L;
                while (System.currentTimeMillis() < stopDeadline) {
                    if (!CoreReadyProbe.probeOnce(port)) {
                        break;
                    }
                    try {
                        Thread.sleep(150L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                try {
                    Thread.sleep(350L);
                } catch (InterruptedException ignored) {
                }

                updateState(STATE_RESTORING, "Restoring files\u2026 20%", 20);

                // 2. Stream-extract validated entries atomically, cleaning companion WAL/SHM per DB on commit
                int restoredCount = 0;
                long extractedBytes = 0L;
                int entriesSeen = 0;
                int lastPct = 20;
                byte[] buf = new byte[65536];
                Set<String> restoredPluginNames = new HashSet<>();
                Set<String> extractedWalPaths = new HashSet<>();

                try (InputStream rawIn = appContext.getContentResolver().openInputStream(uri)) {
                    if (rawIn == null) {
                        throw new IOException("Cannot reopen backup file stream");
                    }
                    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(rawIn, 65536))) {
                        ZipEntry ze;
                        while ((ze = zis.getNextEntry()) != null) {
                            entriesSeen++;
                            if (entriesSeen > MAX_RESTORE_ENTRIES) {
                                throw new IOException("Archive exceeded entry limit");
                            }
                            String name = ze.getName();
                            if (MANIFEST_ENTRY.equals(name) || ze.isDirectory()) {
                                zis.closeEntry();
                                continue;
                            }
                            if (!isAllowedRestoreRelPath(name)) {
                                zis.closeEntry();
                                continue;
                            }
                            File dest = new File(filesDir, name);
                            if (!PathSafety.isInside(filesDir, dest) || hasSymlinkParent(filesDir, dest)) {
                                throw new IOException("Blocked unsafe path in archive: " + name);
                            }
                            if (dest.exists() && isSymlink(dest)) {
                                dest.delete();
                            }
                            File parent = dest.getParentFile();
                            if (parent != null && !parent.exists()) {
                                parent.mkdirs();
                            }

                            File tmp = new File(parent, "." + dest.getName() + ".agy_restore_tmp");
                            if (tmp.exists()) {
                                tmp.delete();
                            }
                            boolean committed = false;
                            try {
                                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                                    int r;
                                    while ((r = zis.read(buf)) > 0) {
                                        extractedBytes += r;
                                        if (extractedBytes > MAX_RESTORE_BYTES) {
                                            throw new IOException("Archive exceeded uncompressed size limit");
                                        }
                                        fos.write(buf, 0, r);
                                    }
                                }
                                tmp.setReadable(true, false);
                                if (validated.execPaths.contains(name) || looksExecutablePath(name)) {
                                    tmp.setExecutable(true, false);
                                }
                                if (!tmp.renameTo(dest)) {
                                    if (dest.exists()) {
                                        dest.delete();
                                    }
                                    if (!tmp.renameTo(dest)) {
                                        throw new IOException("Failed to commit restored file: " + name);
                                    }
                                }
                                committed = true;
                            } finally {
                                if (!committed && tmp.exists()) {
                                    tmp.delete();
                                }
                            }

                            if (name.endsWith(".db-wal")) {
                                extractedWalPaths.add(name);
                            } else if (name.endsWith(".db")) {
                                File shm = new File(dest.getAbsolutePath() + "-shm");
                                if (shm.isFile() && !isSymlink(shm)) {
                                    shm.delete();
                                }
                                if (!extractedWalPaths.contains(name + "-wal")) {
                                    File wal = new File(dest.getAbsolutePath() + "-wal");
                                    if (wal.isFile() && !isSymlink(wal)) {
                                        wal.delete();
                                    }
                                }
                            }
                            if (ze.getTime() > 0) {
                                dest.setLastModified(ze.getTime());
                            }
                            if (name.startsWith(".gemini/config/plugins/")) {
                                String rest = name.substring(".gemini/config/plugins/".length());
                                int slash = rest.indexOf('/');
                                if (slash > 0) {
                                    restoredPluginNames.add(rest.substring(0, slash));
                                }
                            }
                            restoredCount++;
                            if (validated.uncompressedBytes > 0) {
                                int pct = (int) Math.min(92L, 20L + (extractedBytes * 72L) / validated.uncompressedBytes);
                                if (pct >= lastPct + 3) {
                                    lastPct = pct;
                                    updateState(STATE_RESTORING, "Restoring files\u2026 " + pct + "%", pct);
                                }
                            }
                            zis.closeEntry();
                        }
                    }
                }

                // 4. Re-run Android plugin normalization & CLI wrapper registration for restored plugins
                if (!restoredPluginNames.isEmpty()) {
                    updateState(STATE_RESTORING, "Configuring restored plugins\u2026 95%", 95);
                    EngineBridge eb = new EngineBridge(new RuntimeManager(appContext));
                    for (String plug : restoredPluginNames) {
                        try {
                            eb.postProcessInstalledPlugin(plug);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                // 5. Restart CoreServerService (MainActivity.watchCore() automatically reloads WebView)
                updateState(STATE_RESTORING, "Restarting engine\u2026 98%", 98);
                CoreServiceLauncher.start(appContext);
                serviceStopped = false;

                String doneMsg = "Restored " + restoredCount + " files (" + formatBytes(extractedBytes) + "). Reloading\u2026";
                updateState(STATE_SUCCESS, doneMsg, 100);
                showToast("Backup restored! Reloading workspace\u2026");
                refreshStatsAsync(true);
            } catch (Throwable t) {
                Log.e(TAG, "Backup restore failed", t);
                try {
                    CoreServiceLauncher.start(appContext);
                } catch (Throwable ignored) {
                }
                if (!serviceStopped) {
                    mainHandler.post(() -> {
                        MainActivity liveAct = MainActivity.activeActivity;
                        if (liveAct != null && !liveAct.isFinishing() && !liveAct.isDestroyed()) {
                            liveAct.dismissSplash();
                        }
                    });
                }
                String err = "Restore failed: " + safeMsg(t);
                updateState(STATE_ERROR, err, 0);
                showToast(err);
            } finally {
                busy.set(false);
            }
        }, "AgyBackupRestore");
        restorer.setDaemon(true);
        restorer.start();
    }

    private ValidatedBackup preflightValidateArchive(Uri uri) throws Exception {
        File filesDir = appContext.getFilesDir();
        JSONObject manifest = null;
        int fileCount = 0;
        int totalEntries = 0;
        long totalBytes = 0L;
        byte[] buf = new byte[65536];

        try (InputStream rawIn = appContext.getContentResolver().openInputStream(uri)) {
            if (rawIn == null) {
                throw new IOException("Cannot read selected file");
            }
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(rawIn, 65536))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    totalEntries++;
                    if (totalEntries > MAX_RESTORE_ENTRIES) {
                        throw new IOException("Archive exceeds maximum entry count");
                    }
                    String name = ze.getName();
                    if (name == null || name.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }
                    if (MANIFEST_ENTRY.equals(name)) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        int r;
                        while ((r = zis.read(buf)) > 0) {
                            if (baos.size() + r > MAX_MANIFEST_BYTES) {
                                throw new IOException("Backup manifest is too large");
                            }
                            baos.write(buf, 0, r);
                        }
                        manifest = new JSONObject(new String(baos.toByteArray(), StandardCharsets.UTF_8));
                        zis.closeEntry();
                        continue;
                    }
                    if (ze.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }
                    if (!isAllowedRestoreRelPath(name)) {
                        throw new IOException("Disallowed path in backup: " + name);
                    }
                    File target = new File(filesDir, name);
                    if (!PathSafety.isInside(filesDir, target) || hasSymlinkParent(filesDir, target)) {
                        throw new IOException("Unsafe target path in backup: " + name);
                    }
                    int r;
                    while ((r = zis.read(buf)) > 0) {
                        totalBytes += r;
                        if (totalBytes > MAX_RESTORE_BYTES) {
                            throw new IOException("Archive exceeds 4 GB uncompressed limit");
                        }
                    }
                    fileCount++;
                    zis.closeEntry();
                }
            }
        }

        if (manifest == null) {
            throw new IOException("Missing " + MANIFEST_ENTRY + " (not an Antigravity backup)");
        }
        if (!BACKUP_MAGIC.equals(manifest.optString("magic", ""))) {
            throw new IOException("Invalid backup header");
        }
        int schema = manifest.optInt("schemaVersion", 0);
        if (schema < 1 || schema > SCHEMA_VERSION) {
            throw new IOException("Unsupported backup schema version: " + schema);
        }
        if (fileCount == 0) {
            throw new IOException("Backup archive contains no restorable files");
        }

        long usable = filesDir.getUsableSpace();
        if (usable > 0 && usable < totalBytes + MIN_FREE_HEADROOM_BYTES) {
            throw new IOException("Insufficient internal storage ("
                    + formatBytes(usable) + " free, need " + formatBytes(totalBytes + MIN_FREE_HEADROOM_BYTES) + ")");
        }

        ValidatedBackup out = new ValidatedBackup();
        out.createdAt = manifest.optString("createdAt", "Unknown date");
        out.appVersion = manifest.optString("appVersion", "");
        out.entryCount = fileCount;
        out.uncompressedBytes = totalBytes;

        JSONObject scopes = manifest.optJSONObject("scopes");
        if (scopes != null) {
            out.hasConversations = scopes.optBoolean("conversations", false);
            out.hasSkillsPlugins = scopes.optBoolean("skillsPlugins", false);
            out.hasProjects = scopes.optBoolean("projects", false);
        }
        JSONArray execArr = manifest.optJSONArray("execPaths");
        if (execArr != null) {
            for (int i = 0; i < execArr.length(); i++) {
                String ep = execArr.optString(i, "");
                if (!ep.isEmpty()) {
                    out.execPaths.add(ep);
                }
            }
        }
        return out;
    }

    private static List<ExportItem> collectExportItems(File filesDir,
                                                       boolean doConv,
                                                       boolean doSkills,
                                                       boolean doProj) throws IOException {
        List<ExportItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (doConv) {
            addTreeOrFile(filesDir, ".gemini/antigravity-app/conversations", out, seen);
            addTreeOrFile(filesDir, ".gemini/antigravity-app/conversation_summaries.db", out, seen);
            addTreeOrFile(filesDir, ".gemini/antigravity-app/conversation_summaries.db-wal", out, seen);
            addTreeOrFile(filesDir, ".gemini/antigravity-app/agyhub_summaries_proto.pb", out, seen);
            addTreeOrFile(filesDir, ".gemini/antigravity-app/annotations", out, seen);
            addTreeOrFile(filesDir, ".gemini/antigravity-app/brain", out, seen);
        }

        if (doSkills) {
            addTreeOrFile(filesDir, ".gemini/config/skills", out, seen);
            addTreeOrFile(filesDir, ".gemini/config/agents", out, seen);
            addTreeOrFile(filesDir, ".gemini/config/sidecars", out, seen);
            addTreeOrFile(filesDir, ".gemini/config/AGENTS.md", out, seen);
            addTreeOrFile(filesDir, ".gemini/config/plugins", out, seen);
            addTreeOrFile(filesDir, ".gemini/antigravity-app/skills", out, seen);
            addTreeOrFile(filesDir, ".gemini/antigravity-app/plugins", out, seen);
        }

        if (doProj) {
            addTreeOrFile(filesDir, ".gemini/config/projects", out, seen);
            addTreeOrFile(filesDir, ".gemini/config/config.json", out, seen);
            addTreeOrFile(filesDir, ".gemini/config/mcp_config.json", out, seen);
            addTreeOrFile(filesDir, ".gemini/config/.migrated", out, seen);
            addTreeOrFile(filesDir, ".gemini/antigravity-app/antigravity_state.pbtxt", out, seen);
            addTreeOrFile(filesDir, ".gemini/antigravity-app/jetski_state.pbtxt", out, seen);
            addTreeOrFile(filesDir, "workspace", out, seen);
        }

        Collections.sort(out, (a, b) -> a.relPath.compareTo(b.relPath));
        return out;
    }

    private static void addTreeOrFile(File filesDir,
                                      String relRoot,
                                      List<ExportItem> out,
                                      Set<String> seen) throws IOException {
        File node = new File(filesDir, relRoot);
        if (!node.exists() || isSymlink(node) || !PathSafety.isInside(filesDir, node)) {
            return;
        }
        walkExportNode(filesDir, node, relRoot, out, seen);
    }

    private static void walkExportNode(File filesDir,
                                       File cur,
                                       String relPath,
                                       List<ExportItem> out,
                                       Set<String> seen) throws IOException {
        if (isSymlink(cur) || !PathSafety.isInside(filesDir, cur)) {
            return;
        }
        if (isSecurityBlockedRelPath(relPath) || isTransientExportSkipRelPath(relPath)) {
            return;
        }
        if (cur.isDirectory()) {
            File[] children = cur.listFiles();
            if (children == null) {
                return;
            }
            Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
            for (File child : children) {
                String childName = child.getName();
                if (childName == null || childName.isEmpty()) continue;
                walkExportNode(filesDir, child, relPath + "/" + childName, out, seen);
            }
            return;
        }
        if (cur.isFile() && seen.add(relPath)) {
            out.add(new ExportItem(relPath, cur, cur.canExecute()));
        }
    }

    private static boolean isAllowedRestoreRelPath(String relPath) {
        if (relPath == null || relPath.isEmpty()) return false;
        if (relPath.startsWith("/") || relPath.contains("\\") || relPath.contains("\0")) return false;
        String[] parts = relPath.split("/");
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p) || "..".equals(p)) {
                return false;
            }
        }
        boolean allowedRoot = relPath.startsWith(".gemini/antigravity-app/")
                || relPath.startsWith(".gemini/config/")
                || relPath.startsWith("workspace/");
        if (!allowedRoot) {
            return false;
        }
        return !isSecurityBlockedRelPath(relPath);
    }

    /** Strictly blocked on BOTH export and restore (credentials, OAuth sessions, private keys). */
    private static boolean isSecurityBlockedRelPath(String relPath) {
        if (relPath == null) return true;
        String lower = relPath.toLowerCase(Locale.US);
        if (lower.equals(".gemini/jetski-standalone-oauth-token")
                || lower.startsWith(".gemini/antigravity/plugin_data")
                || lower.startsWith(".gemini/antigravity-app/plugin_data")
                || lower.startsWith(".keys/")
                || lower.startsWith(".ssh/")
                || lower.startsWith(".android/")) {
            return true;
        }
        int slash = lower.lastIndexOf('/');
        String base = slash >= 0 ? lower.substring(slash + 1) : lower;
        return base.equals("token.json")
                || base.equals("tokens.json")
                || base.equals("credentials.json")
                || base.equals("signing.env")
                || base.equals(".netrc")
                || base.equals("adbkey")
                || base.equals("adbkey.pub")
                || base.equals("id_rsa")
                || base.equals("id_ed25519")
                || base.endsWith(".jks")
                || base.endsWith(".keystore")
                || base.endsWith(".p12")
                || base.endsWith(".pfx")
                || base.endsWith(".agy_restore_tmp");
    }

    /** Skipped during export & size estimation to avoid transient scratch/cache/duplicate log bloat. */
    private static boolean isTransientExportSkipRelPath(String relPath) {
        if (relPath == null) return true;
        String lower = relPath.toLowerCase(Locale.US);
        int slash = lower.lastIndexOf('/');
        String base = slash >= 0 ? lower.substring(slash + 1) : lower;
        if (base.endsWith(".db-shm") || base.endsWith(".db-journal")) {
            return true;
        }
        if (lower.startsWith(".gemini/antigravity-app/")) {
            if (lower.endsWith("/scratch")
                    || lower.contains("/scratch/")
                    || lower.endsWith("/.system_generated/steps")
                    || lower.contains("/.system_generated/steps/")
                    || lower.endsWith("/.system_generated/tasks")
                    || lower.contains("/.system_generated/tasks/")
                    || lower.endsWith("/.system_generated/logs/chunks")
                    || lower.contains("/.system_generated/logs/chunks/")
                    || lower.startsWith(".gemini/antigravity-app/cache")
                    || lower.startsWith(".gemini/antigravity-app/crashes")) {
                return true;
            }
        }
        if (lower.startsWith(".gemini/config/plugins/.staging-")
                || lower.startsWith(".gemini/config/plugins/.backup-")) {
            return true;
        }
        return relPath.equals("workspace/.bashrc") || relPath.equals("workspace/.profile");
    }

    private static boolean looksExecutablePath(String relPath) {
        return relPath.contains("/bin/")
                || relPath.contains("/scripts/")
                || relPath.endsWith(".sh");
    }

    private static boolean isSymlink(File file) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasSymlinkParent(File root, File target) {
        File cur = target.getParentFile();
        while (cur != null && !cur.equals(root)) {
            if (cur.exists() && isSymlink(cur)) {
                return true;
            }
            cur = cur.getParentFile();
        }
        return false;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "Unknown error";
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m.trim();
    }

    private static final class ExportItem {
        final String relPath;
        final File file;
        final boolean executable;

        ExportItem(String relPath, File file, boolean executable) {
            this.relPath = relPath;
            this.file = file;
            this.executable = executable;
        }
    }

    private static final class ValidatedBackup {
        String createdAt = "";
        String appVersion = "";
        int entryCount = 0;
        long uncompressedBytes = 0L;
        boolean hasConversations = false;
        boolean hasSkillsPlugins = false;
        boolean hasProjects = false;
        Set<String> execPaths = new HashSet<>();

        String describeScopes() {
            List<String> parts = new ArrayList<>();
            if (hasConversations) parts.add("Conversations & Artifacts");
            if (hasSkillsPlugins) parts.add("Skills & Plugins");
            if (hasProjects) parts.add("Projects & Settings");
            return parts.isEmpty() ? "User data" : String.join(", ", parts);
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream out;
        long count = 0L;

        CountingOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
