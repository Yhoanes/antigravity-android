package com.agy.bridge;

import com.agy.core.CoreRuntimeState;
import com.agy.runtime.RuntimeManager;
import com.agy.util.CoreUrl;

/** Read-only engine status exposed to the Web UI. */
public final class EngineBridge {

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
            String urlStr = (archiveUrl != null && !archiveUrl.trim().isEmpty())
                    ? archiveUrl.trim()
                    : ("https://www.gstatic.com/antigravity/plugins/" + safeName + "/" + safeName + ".zip");
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
            conn.setReadTimeout(20000);
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
                        if (entry.getName().contains("/scripts/") || entry.getName().contains("/bin/")) {
                            outFile.setExecutable(true, false);
                        }
                    }
                    zis.closeEntry();
                }
            } finally {
                conn.disconnect();
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
