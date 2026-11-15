package com.agy.bridge;

import android.system.Os;
import android.system.OsConstants;

import com.agy.core.CoreRuntimeState;
import com.agy.runtime.RuntimeManager;
import com.agy.util.CoreUrl;
import com.agy.util.FileModeUtil;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Engine status and Android-native Marketplace plugin installer exposed to the Web UI. */
public final class EngineBridge {

    private static final String ANDROID_PLUGINS_RAW_BASE =
            "https://raw.githubusercontent.com/Yhoanes/antigravity-android-plugins/main/plugins/";

    private static final Pattern SAFE_PLUGIN_NAME =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");

    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;
    private static final int HOOK_TIMEOUT_SEC = 20;
    private static final int MAX_HOOK_LOG_BYTES = 16 * 1024;

    private final RuntimeManager runtime;

    public EngineBridge(RuntimeManager runtime) {
        this.runtime = runtime;
    }

    public boolean isReady() {
        return CoreRuntimeState.serverUrl != null;
    }

    public int getPort() {
        return runtime.getHttpsPort();
    }

    public String getBaseUrl() {
        String url = CoreRuntimeState.serverUrl;
        if (url != null) {
            int q = url.indexOf('?');
            return q >= 0 ? url.substring(0, q) : url;
        }
        return CoreUrl.baseUrl(runtime.getHttpsPort());
    }

    public String getCsrfToken() {
        String token = CoreRuntimeState.csrfToken;
        return token != null ? token : runtime.getCsrfToken();
    }

    static String validatePluginName(String pluginName) {
        if (pluginName == null) {
            return null;
        }
        String trimmed = pluginName.trim();
        if (trimmed.isEmpty() || ".".equals(trimmed) || "..".equals(trimmed) || trimmed.contains("..")) {
            return null;
        }
        if (!SAFE_PLUGIN_NAME.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    private static boolean isSymlink(File file) {
        if (file == null) {
            return false;
        }
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (Throwable t) {
            try {
                File parent = file.getParentFile();
                File canonParent = parent != null ? parent.getCanonicalFile() : null;
                File ref = canonParent != null ? new File(canonParent, file.getName()) : file;
                return !ref.getCanonicalFile().equals(ref.getAbsoluteFile());
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    private static File resolveSafePluginChild(File pluginsDir, String childName) throws IOException {
        if (!pluginsDir.exists() && !pluginsDir.mkdirs() && !pluginsDir.isDirectory()) {
            throw new IOException("Cannot create plugins root directory");
        }
        File canonicalRoot = pluginsDir.getCanonicalFile();
        File candidate = new File(canonicalRoot, childName);
        if (isSymlink(candidate)) {
            throw new SecurityException("Refusing symlink plugin directory");
        }
        File canonicalCandidate = candidate.getCanonicalFile();
        if (!canonicalRoot.equals(canonicalCandidate.getParentFile())
                || canonicalRoot.equals(canonicalCandidate)) {
            throw new SecurityException("Plugin path escapes plugins root");
        }
        return candidate;
    }

    private static String resolveAndroidOverrideUrl(String safeName, String archiveUrl) {
        if ("agy-video-studio".equals(safeName)
                || "craft-anything".equals(safeName)
                || "chrome-devtools-plugin".equals(safeName)
                || "android-cli-plugin".equals(safeName)
                || "science".equals(safeName)) {
            if (archiveUrl == null || archiveUrl.isEmpty() || archiveUrl.contains("gstatic.com")) {
                return ANDROID_PLUGINS_RAW_BASE + safeName + "/" + safeName + ".zip";
            }
        }
        if (archiveUrl != null && !archiveUrl.trim().isEmpty()) {
            return archiveUrl.trim();
        }
        return "https://www.gstatic.com/antigravity/plugins/" + safeName + "/" + safeName + ".zip";
    }

    public String installMarketplacePlugin(String pluginName, String archiveUrl,
                                           String catalogId, String marketplace, String version) {
        File stagingDir = null;
        File backupDir = null;
        try {
            String safeName = validatePluginName(pluginName);
            if (safeName == null) {
                return "{\"success\":false,\"message\":\"Invalid plugin name\"}";
            }
            String urlStr = resolveAndroidOverrideUrl(safeName, archiveUrl);
            File configDir = new File(runtime.getGeminiDir(), "config");
            File pluginsDir = new File(configDir, "plugins");
            File targetDir = resolveSafePluginChild(pluginsDir, safeName);

            long stamp = System.currentTimeMillis();
            stagingDir = resolveSafePluginChild(pluginsDir, ".staging-" + safeName + "-" + stamp);
            deleteRecursiveSafe(pluginsDir, stagingDir);
            if (!stagingDir.mkdirs() && !stagingDir.isDirectory()) {
                return "{\"success\":false,\"message\":\"Cannot create staging directory\"}";
            }
            String canonicalStaging = stagingDir.getCanonicalPath();

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            int extractedFiles = 0;
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("Download failed with HTTP " + code);
                }
                try (InputStream in = conn.getInputStream();
                     ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
                    ZipEntry entry;
                    byte[] buf = new byte[16384];
                    int entryCount = 0;
                    long totalBytes = 0L;
                    while ((entry = zis.getNextEntry()) != null) {
                        entryCount++;
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            throw new IOException("Plugin archive exceeds entry quota");
                        }
                        File outFile = new File(stagingDir, entry.getName());
                        String canonicalOut = outFile.getCanonicalPath();
                        if (!canonicalOut.startsWith(canonicalStaging + File.separator)
                                && !canonicalOut.equals(canonicalStaging)) {
                            zis.closeEntry();
                            continue;
                        }
                        if (entry.isDirectory()) {
                            outFile.mkdirs();
                        } else {
                            File parent = outFile.getParentFile();
                            if (parent != null && !parent.exists()) {
                                parent.mkdirs();
                            }
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                int n;
                                while ((n = zis.read(buf)) > 0) {
                                    totalBytes += n;
                                    if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                        throw new IOException("Plugin archive exceeds size quota");
                                    }
                                    fos.write(buf, 0, n);
                                }
                            }
                            extractedFiles++;
                        }
                        zis.closeEntry();
                    }
                }
            } finally {
                conn.disconnect();
            }

            if (extractedFiles == 0) {
                throw new IOException("Downloaded archive was empty or invalid");
            }

            postProcessPluginDir(stagingDir, true);

            if (targetDir.exists()) {
                backupDir = resolveSafePluginChild(pluginsDir, ".backup-" + safeName + "-" + stamp);
                deleteRecursiveSafe(pluginsDir, backupDir);
                if (!targetDir.renameTo(backupDir)) {
                    throw new IOException("Cannot stage previous plugin backup");
                }
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && backupDir.exists()) {
                    backupDir.renameTo(targetDir);
                }
                throw new IOException("Cannot activate staged plugin directory");
            }
            stagingDir = null;

            if (backupDir != null && backupDir.exists()) {
                deleteRecursiveSafe(pluginsDir, backupDir);
                backupDir = null;
            }

            updateConfigJsonOnInstall(configDir, safeName,
                    catalogId != null && !catalogId.isEmpty() ? catalogId : ("antigravity-plugins-official/plugin/" + safeName),
                    marketplace != null && !marketplace.isEmpty() ? marketplace : "antigravity-plugins-official",
                    version != null && !version.isEmpty() ? version : "1.0.0");
            org.json.JSONObject ok = new org.json.JSONObject();
            ok.put("success", true);
            ok.put("installDir", safeName);
            return ok.toString();
        } catch (Exception e) {
            if (stagingDir != null && stagingDir.exists()) {
                try {
                    File pluginsDir = new File(new File(runtime.getGeminiDir(), "config"), "plugins");
                    deleteRecursiveSafe(pluginsDir, stagingDir);
                } catch (Exception ignored) {
                }
            }
            try {
                org.json.JSONObject err = new org.json.JSONObject();
                err.put("success", false);
                err.put("message", e.getMessage() != null ? e.getMessage() : "Install failed");
                return err.toString();
            } catch (Exception ignored) {
                return "{\"success\":false,\"message\":\"Install failed\"}";
            }
        }
    }

    /**
     * Applies Android ARM64/Bionic normalization to an already-extracted plugin directory
     * (used both by installMarketplacePlugin and after official language_server installs).
     */
    public String postProcessInstalledPlugin(String pluginName) {
        try {
            String safeName = validatePluginName(pluginName);
            if (safeName == null) {
                return "{\"success\":false}";
            }
            File configDir = new File(runtime.getGeminiDir(), "config");
            File pluginsDir = new File(configDir, "plugins");
            File targetDir = resolveSafePluginChild(pluginsDir, safeName);
            if (!targetDir.isDirectory()) {
                return "{\"success\":false}";
            }
            postProcessPluginDir(targetDir, false);
            return "{\"success\":true}";
        } catch (Exception e) {
            return "{\"success\":false}";
        }
    }

    private void postProcessPluginDir(File targetDir, boolean strictHook) throws IOException {
        if (targetDir == null || !targetDir.isDirectory()) return;
        File filesDir = runtime.appContext().getFilesDir();
        String homePath = filesDir.getAbsolutePath();

        // 1. Strip incompatible desktop Electron sidecars (x86_64/macOS binaries)
        File sidecarsDir = new File(targetDir, "sidecars");
        if (sidecarsDir.exists()) {
            deleteSubtreeNoSymlinks(sidecarsDir);
        }

        // 2. Sanitize mcp_config.json (remove dead desktop sidecar servers, fix hardcoded home path)
        File mcpCfg = new File(targetDir, "mcp_config.json");
        if (mcpCfg.isFile()) {
            try {
                org.json.JSONObject root = readJsonObject(mcpCfg);
                org.json.JSONObject servers = root.optJSONObject("mcpServers");
                if (servers != null) {
                    List<String> toRemove = new ArrayList<>();
                    Iterator<String> keys = servers.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        org.json.JSONObject srv = servers.optJSONObject(k);
                        if (srv == null) continue;
                        String srvStr = srv.toString();
                        if (srvStr.contains("sidecars/")) {
                            toRemove.add(k);
                        }
                    }
                    for (String k : toRemove) {
                        servers.remove(k);
                    }
                    if (servers.length() == 0) {
                        mcpCfg.delete();
                    } else {
                        String updated = root.toString(2)
                                .replace("/data/user/0/com.agy/files", homePath) + "\n";
                        com.agy.util.AtomicFileWriter.writeText(mcpCfg, updated);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Walk files: strip CRLF on scripts & set 0755 on bin/scripts/install-plugin.sh
        normalizePluginFilesRecursive(targetDir, targetDir, homePath);

        // 4. Execute install-plugin.sh if bundled, draining output and enforcing timeout
        File installSh = new File(targetDir, "install-plugin.sh");
        if (installSh.isFile() && !isSymlink(installSh)) {
            FileModeUtil.setExec(installSh);
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", installSh.getAbsolutePath());
                pb.directory(targetDir);
                pb.redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.put("HOME", homePath);
                env.put("PREFIX", new File(filesDir, "usr").getAbsolutePath());
                env.put("ANTIGRAVITY_APP_DATA_DIR", runtime.getAppDataDir().getAbsolutePath());
                env.remove("LD_PRELOAD");
                env.remove("LD_LIBRARY_PATH");
                p = pb.start();
                final InputStream procOut = p.getInputStream();
                final ByteArrayOutputStream logCapture = new ByteArrayOutputStream();
                Thread drain = new Thread(() -> {
                    byte[] b = new byte[4096];
                    int r;
                    try {
                        while ((r = procOut.read(b)) != -1) {
                            int room = MAX_HOOK_LOG_BYTES - logCapture.size();
                            if (room > 0) {
                                logCapture.write(b, 0, Math.min(r, room));
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }, "PluginHookDrain");
                drain.setDaemon(true);
                drain.start();

                boolean finished = p.waitFor(HOOK_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    drain.join(500);
                    if (strictHook) {
                        throw new IOException("Plugin install hook timed out");
                    }
                    return;
                }
                drain.join(1000);
                int exit = p.exitValue();
                if (exit != 0 && strictHook) {
                    String tail = new String(logCapture.toByteArray(), StandardCharsets.UTF_8).trim();
                    throw new IOException("Plugin install hook failed (exit " + exit + ")"
                            + (tail.isEmpty() ? "" : ": " + tail));
                }
            } catch (IOException ioe) {
                if (strictHook) {
                    throw ioe;
                }
            } catch (Exception e) {
                if (p != null) {
                    p.destroyForcibly();
                }
                if (strictHook) {
                    throw new IOException("Plugin install hook error: " + e.getMessage(), e);
                }
            }
        }
    }

    private static void normalizePluginFilesRecursive(File root, File cur, String homePath) {
        File[] children = cur.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (isSymlink(f)) {
                continue;
            }
            if (f.isDirectory()) {
                normalizePluginFilesRecursive(root, f, homePath);
                continue;
            }
            String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length()).replace('\\', '/');
            String name = f.getName();
            boolean inExecDir = rel.contains("/bin/") || rel.contains("/scripts/") || "/install-plugin.sh".equals(rel);
            boolean isScriptExt = name.endsWith(".sh") || name.endsWith(".py") || name.endsWith(".mjs") || name.endsWith(".cjs");
            if ((inExecDir || isScriptExt) && f.length() > 0 && f.length() < 2 * 1024 * 1024) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] raw = new byte[(int) f.length()];
                    int off = 0;
                    while (off < raw.length) {
                        int n = fis.read(raw, off, raw.length - off);
                        if (n <= 0) break;
                        off += n;
                    }
                    // Skip binary ELF files
                    if (!(raw.length >= 4 && raw[0] == 0x7F && raw[1] == 'E' && raw[2] == 'L' && raw[3] == 'F')) {
                        String txt = new String(raw, 0, off, StandardCharsets.UTF_8);
                        if (txt.indexOf('\r') >= 0 || txt.contains("/data/user/0/com.agy/files")) {
                            String clean = txt.replace("\r\n", "\n")
                                    .replace("/data/user/0/com.agy/files", homePath);
                            com.agy.util.AtomicFileWriter.writeText(f, clean);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (inExecDir || name.endsWith(".sh")) {
                FileModeUtil.setExec(f);
            }
        }
    }

    public boolean uninstallMarketplacePlugin(String pluginName) {
        try {
            String safeName = validatePluginName(pluginName);
            if (safeName == null) {
                return false;
            }
            File configDir = new File(runtime.getGeminiDir(), "config");
            File pluginsDir = new File(configDir, "plugins");
            File targetDir = resolveSafePluginChild(pluginsDir, safeName);
            deleteRecursiveSafe(pluginsDir, targetDir);
            File runtimeBin = new File(runtime.appContext().getFilesDir(), "runtime/bin");
            if ("craft-anything".equals(safeName)) {
                new File(runtimeBin, "craft-anything-cli").delete();
                new File(runtimeBin, "craft-ghidra-setup").delete();
            } else if ("agy-video-studio".equals(safeName)) {
                new File(runtimeBin, "agy-video-setup").delete();
                new File(runtimeBin, "agy-video-mcp").delete();
            }
            updateConfigJsonOnUninstall(configDir, safeName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursiveSafe(File pluginsDir, File target) throws IOException {
        if (target == null || !target.exists()) return;
        File canonicalRoot = pluginsDir.getCanonicalFile();
        File canonicalTarget = target.getCanonicalFile();
        if (!canonicalRoot.equals(canonicalTarget.getParentFile())
                || canonicalRoot.equals(canonicalTarget)) {
            throw new SecurityException("Refusing recursive delete outside plugins root");
        }
        deleteSubtreeNoSymlinks(target);
    }

    private static void deleteSubtreeNoSymlinks(File f) {
        if (f == null || (!f.exists() && !isSymlink(f))) return;
        if (!isSymlink(f) && f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteSubtreeNoSymlinks(c);
                }
            }
        }
        f.delete();
    }

    private static void updateConfigJsonOnInstall(File configDir, String safeName,
                                                  String id, String marketplace, String version) {
        try {
            File cfgFile = new File(configDir, "config.json");
            org.json.JSONObject root = readJsonObject(cfgFile);
            org.json.JSONObject plugins = root.optJSONObject("plugins");
            if (plugins == null) {
                plugins = new org.json.JSONObject();
                root.put("plugins", plugins);
            }
            org.json.JSONObject pObj = plugins.optJSONObject(safeName);
            if (pObj == null) {
                pObj = new org.json.JSONObject();
                plugins.put(safeName, pObj);
            }
            pObj.put("enabled", true);
            pObj.remove("disabled");
            org.json.JSONObject from = new org.json.JSONObject();
            from.put("id", id);
            from.put("marketplace", marketplace);
            from.put("version", version);
            pObj.put("installedFrom", from);
            com.agy.util.AtomicFileWriter.writeText(cfgFile, root.toString(2) + "\n");
        } catch (Exception ignored) {
        }
    }

    private static void updateConfigJsonOnUninstall(File configDir, String safeName) {
        try {
            File cfgFile = new File(configDir, "config.json");
            if (!cfgFile.isFile()) return;
            org.json.JSONObject root = readJsonObject(cfgFile);
            org.json.JSONObject plugins = root.optJSONObject("plugins");
            if (plugins != null && plugins.has(safeName)) {
                plugins.remove(safeName);
                com.agy.util.AtomicFileWriter.writeText(cfgFile, root.toString(2) + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    private static org.json.JSONObject readJsonObject(File f) {
        if (f != null && f.isFile()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                String txt = com.agy.util.IoUtil.readAll(fis, "UTF-8").trim();
                if (!txt.isEmpty()) {
                    return new org.json.JSONObject(txt);
                }
            } catch (Exception ignored) {
            }
        }
        return new org.json.JSONObject();
    }
}
