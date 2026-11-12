package com.agy.bridge;

import com.agy.core.CoreRuntimeState;
import com.agy.runtime.RuntimeManager;
import com.agy.util.CoreUrl;
import com.agy.util.FileModeUtil;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/** Engine status and Android-native Marketplace plugin installer exposed to the Web UI. */
public final class EngineBridge {

    private static final String ANDROID_PLUGINS_RAW_BASE =
            "https://raw.githubusercontent.com/Yhoanes/antigravity-android-plugins/main/plugins/";

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

    private static String resolveAndroidOverrideUrl(String safeName, String archiveUrl) {
        if ("agy-video-studio".equals(safeName)
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
        try {
            if (pluginName == null || pluginName.trim().isEmpty()) {
                return "{\"success\":false,\"message\":\"Invalid plugin name\"}";
            }
            String safeName = pluginName.trim().replaceAll("[^a-zA-Z0-9._-]", "");
            if (safeName.isEmpty()) {
                return "{\"success\":false,\"message\":\"Invalid plugin name\"}";
            }
            String urlStr = resolveAndroidOverrideUrl(safeName, archiveUrl);
            java.io.File configDir = new java.io.File(runtime.getGeminiDir(), "config");
            java.io.File pluginsDir = new java.io.File(configDir, "plugins");
            java.io.File targetDir = new java.io.File(pluginsDir, safeName);
            deleteRecursive(targetDir);
            if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
                return "{\"success\":false,\"message\":\"Cannot create plugin directory\"}";
            }
            String canonicalTarget = targetDir.getCanonicalPath();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(true);
            try (java.io.InputStream in = conn.getInputStream();
                 java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(in))) {
                java.util.zip.ZipEntry entry;
                byte[] buf = new byte[16384];
                while ((entry = zis.getNextEntry()) != null) {
                    java.io.File outFile = new java.io.File(targetDir, entry.getName());
                    String canonicalOut = outFile.getCanonicalPath();
                    if (!canonicalOut.startsWith(canonicalTarget + java.io.File.separator)
                            && !canonicalOut.equals(canonicalTarget)) {
                        zis.closeEntry();
                        continue;
                    }
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        java.io.File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs();
                        }
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                            int n;
                            while ((n = zis.read(buf)) > 0) {
                                fos.write(buf, 0, n);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            } finally {
                conn.disconnect();
            }
            postProcessPluginDir(targetDir);
            updateConfigJsonOnInstall(configDir, safeName,
                    catalogId != null && !catalogId.isEmpty() ? catalogId : ("antigravity-plugins-official/plugin/" + safeName),
                    marketplace != null && !marketplace.isEmpty() ? marketplace : "antigravity-plugins-official",
                    version != null && !version.isEmpty() ? version : "1.0.0");
            org.json.JSONObject ok = new org.json.JSONObject();
            ok.put("success", true);
            ok.put("installDir", safeName);
            return ok.toString();
        } catch (Exception e) {
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
            if (pluginName == null || pluginName.trim().isEmpty()) {
                return "{\"success\":false}";
            }
            String safeName = pluginName.trim().replaceAll("[^a-zA-Z0-9._-]", "");
            if (safeName.isEmpty()) {
                return "{\"success\":false}";
            }
            java.io.File configDir = new java.io.File(runtime.getGeminiDir(), "config");
            java.io.File targetDir = new java.io.File(new java.io.File(configDir, "plugins"), safeName);
            if (!targetDir.isDirectory()) {
                return "{\"success\":false}";
            }
            postProcessPluginDir(targetDir);
            return "{\"success\":true}";
        } catch (Exception e) {
            return "{\"success\":false}";
        }
    }

    private void postProcessPluginDir(java.io.File targetDir) {
        if (targetDir == null || !targetDir.isDirectory()) return;
        java.io.File filesDir = runtime.appContext().getFilesDir();
        String homePath = filesDir.getAbsolutePath();

        // 1. Strip incompatible desktop Electron sidecars
        java.io.File sidecarsDir = new java.io.File(targetDir, "sidecars");
        if (sidecarsDir.exists()) {
            deleteRecursive(sidecarsDir);
        }

        // 2. Sanitize mcp_config.json (remove dead desktop sidecar servers, fix hardcoded home path)
        java.io.File mcpCfg = new java.io.File(targetDir, "mcp_config.json");
        if (mcpCfg.isFile()) {
            try {
                org.json.JSONObject root = readJsonObject(mcpCfg);
                org.json.JSONObject servers = root.optJSONObject("mcpServers");
                if (servers != null) {
                    java.util.List<String> toRemove = new java.util.ArrayList<>();
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

        // 4. Execute install-plugin.sh if bundled
        java.io.File installSh = new java.io.File(targetDir, "install-plugin.sh");
        if (installSh.isFile()) {
            FileModeUtil.setExec(installSh);
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", installSh.getAbsolutePath());
                pb.directory(targetDir);
                java.util.Map<String, String> env = pb.environment();
                env.put("HOME", homePath);
                env.put("PREFIX", new java.io.File(filesDir, "usr").getAbsolutePath());
                env.put("ANTIGRAVITY_APP_DATA_DIR", runtime.getAppDataDir().getAbsolutePath());
                env.remove("LD_PRELOAD");
                env.remove("LD_LIBRARY_PATH");
                Process p = pb.start();
                p.waitFor(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    private static void normalizePluginFilesRecursive(java.io.File root, java.io.File cur, String homePath) {
        java.io.File[] children = cur.listFiles();
        if (children == null) return;
        for (java.io.File f : children) {
            if (f.isDirectory()) {
                normalizePluginFilesRecursive(root, f, homePath);
                continue;
            }
            String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length()).replace('\\', '/');
            String name = f.getName();
            boolean inExecDir = rel.contains("/bin/") || rel.contains("/scripts/") || "/install-plugin.sh".equals(rel);
            boolean isScriptExt = name.endsWith(".sh") || name.endsWith(".py") || name.endsWith(".mjs") || name.endsWith(".cjs");
            if ((inExecDir || isScriptExt) && f.length() > 0 && f.length() < 2 * 1024 * 1024) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
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
            if (pluginName == null || pluginName.trim().isEmpty()) {
                return false;
            }
            String safeName = pluginName.trim().replaceAll("[^a-zA-Z0-9._-]", "");
            if (safeName.isEmpty()) {
                return false;
            }
            java.io.File configDir = new java.io.File(runtime.getGeminiDir(), "config");
            java.io.File targetDir = new java.io.File(new java.io.File(configDir, "plugins"), safeName);
            deleteRecursive(targetDir);
            updateConfigJsonOnUninstall(configDir, safeName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] children = f.listFiles();
        if (children != null) {
            for (java.io.File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static void updateConfigJsonOnInstall(java.io.File configDir, String safeName,
                                                  String id, String marketplace, String version) {
        try {
            java.io.File cfgFile = new java.io.File(configDir, "config.json");
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
            org.json.JSONObject from = new org.json.JSONObject();
            from.put("id", id);
            from.put("marketplace", marketplace);
            from.put("version", version);
            pObj.put("installedFrom", from);
            com.agy.util.AtomicFileWriter.writeText(cfgFile, root.toString(2) + "\n");
        } catch (Exception ignored) {
        }
    }

    private static void updateConfigJsonOnUninstall(java.io.File configDir, String safeName) {
        try {
            java.io.File cfgFile = new java.io.File(configDir, "config.json");
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

    private static org.json.JSONObject readJsonObject(java.io.File f) {
        if (f != null && f.isFile()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
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
