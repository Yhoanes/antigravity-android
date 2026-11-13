package com.agy.runtime;

import android.content.Context;
import android.util.Log;

import com.agy.util.AtomicFileWriter;
import com.agy.util.FileModeUtil;
import com.agy.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Materializes a fully-onboarded state before the core starts, so the Welcome
 * wizard is skipped. Idempotent and non-destructive: only writes when the
 * target is absent (or a zero-byte stub).
 */
public final class OnboardingSeeder {

    private static final String TAG = "OnboardingSeeder";

    private OnboardingSeeder() {
    }

    public static void seed(Context appContext, File appData, File projects) {
        try {
            if (!appData.exists() && !appData.mkdirs()) {
                Log.w(TAG, "cannot create " + appData);
                return;
            }
            seedStateFile(appContext, "jetski_state.pbtxt", new File(appData, "jetski_state.pbtxt"));
            seedStateFile(appContext, "antigravity_state.pbtxt",
                    new File(appData, "antigravity_state.pbtxt"));
            new File(appData, "plugins").mkdirs();
            new File(appData, "skills").mkdirs();

            // Seed default agent rules (AGENTS.md) and Android runtime settings into ~/.gemini/config/
            File configDir = projects.getParentFile();
            if (configDir != null && (configDir.isDirectory() || configDir.mkdirs())) {
                seedStateFile(appContext, "AGENTS.md", new File(configDir, "AGENTS.md"));
                new File(configDir, "plugins").mkdirs();
                new File(configDir, "skills").mkdirs();
                new File(configDir, "agents").mkdirs();
                new File(configDir, "sidecars").mkdirs();
                seedAndroidRuntimeAndConfig(appContext, configDir);
            }

            // The project store resolves its projects here; without the
            // pseudo-project file it logs project_store_get_file_missing.
            if (!projects.exists() && !projects.mkdirs()) {
                Log.w(TAG, "cannot create " + projects);
                return;
            }
            File outside = new File(projects, "outside-of-project.json");
            if (!outside.isFile()) {
                AtomicFileWriter.writeText(outside, "{}\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "onboarding seed failed (continuing): " + e.getMessage());
        }
    }

    private static void seedAndroidRuntimeAndConfig(Context appContext, File configDir) {
        try {
            File filesDir = appContext.getFilesDir();
            File prefixDir = new File(filesDir, "usr");
            File prefixBin = new File(prefixDir, "bin");
            File runtimeBin = new File(filesDir, RuntimePaths.BIN_DIR);
            File pluginsDir = new File(configDir, "plugins");

            // Strip incompatible desktop Electron sidecars inside any existing plugin dirs
            File[] pluginDirs = pluginsDir.listFiles();
            if (pluginDirs != null) {
                for (File pd : pluginDirs) {
                    if (pd.isDirectory()) {
                        deleteRecursive(new File(pd, "sidecars"));
                    }
                }
            }

            File tmpRun = new File(prefixDir, "tmp/run");
            if (tmpRun.isDirectory() || tmpRun.mkdirs()) {
                try {
                    android.system.Os.chmod(tmpRun.getAbsolutePath(), 0700);
                } catch (Throwable ignored) {
                }
            }
            for (String gdir : new String[]{"tmp/ghidra-temp", "tmp/ghidra-cache", "tmp/ghidra-settings"}) {
                new File(prefixDir, gdir).mkdirs();
            }

            // Clean up any residual $PREFIX/Ghidra directory or leftover cached .deb files
            deleteRecursive(new File(prefixDir, "Ghidra"));
            File pkgCache = new File(prefixDir, "var/lib/pkg/cache");
            File[] cachedDebs = pkgCache.listFiles();
            if (cachedDebs != null) {
                for (File cf : cachedDebs) {
                    if (cf.isFile() && cf.getName().endsWith(".deb")) {
                        cf.delete();
                    }
                }
            }

            // If Ghidra 12.1.4 is already extracted on disk, ensure its analyzeHeadless wrappers in both
            // $PREFIX/bin and $HOME/runtime/bin have JDK_JAVA_OPTIONS and dot-free temp dirs configured.
            File ghidraRoot = new File(prefixDir, "opt/ghidra/ghidra_12.1.4_PUBLIC");
            File ghidraSupportHeadless = new File(ghidraRoot, "support/analyzeHeadless");
            if (ghidraSupportHeadless.isFile()) {
                String headlessScript = "#!/system/bin/sh\n"
                        + "export HOME=\"" + filesDir.getAbsolutePath() + "\"\n"
                        + "export PREFIX=\"" + prefixDir.getAbsolutePath() + "\"\n"
                        + "export JAVA_HOME=\"" + new File(prefixDir, "lib/jvm/java-21-openjdk").getAbsolutePath() + "\"\n"
                        + "export LD_LIBRARY_PATH=\"$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"\n"
                        + "export PATH=\"$PREFIX/bin:$HOME/runtime/bin:$JAVA_HOME/bin:/system/bin\"\n"
                        + "export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"\n"
                        + "export JDK_JAVA_OPTIONS=\"-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp\"\n"
                        + "mkdir -p \"$TMPDIR/ghidra-temp\" \"$TMPDIR/ghidra-cache\" \"$TMPDIR/ghidra-settings\"\n"
                        + "exec \"$PREFIX/bin/bash\" \"" + ghidraSupportHeadless.getAbsolutePath() + "\" \"$@\"\n";
                for (File dst : new File[]{new File(prefixBin, "analyzeHeadless"), new File(runtimeBin, "analyzeHeadless")}) {
                    if (dst.getParentFile() != null && (dst.getParentFile().isDirectory() || dst.getParentFile().mkdirs())) {
                        dst.delete();
                        AtomicFileWriter.writeText(dst, headlessScript);
                        FileModeUtil.setExec(dst);
                    }
                }
            }

            // Configure ~/.gemini/config/config.json:
            // 1. Clean up legacy pre-seeded disabled craft-anything (.bundled_version) so Craft Anything + Ghidra
            //    is managed cleanly via the 'Built for Android' Marketplace shelf (enabled=true on install).
            // 2. Seed Android-compatible userSettings (enableTerminalSandbox=false & MCP tool grants).
            File configJson = new File(configDir, "config.json");
            JSONObject root = new JSONObject();
            if (configJson.isFile() && configJson.length() > 0) {
                try (InputStream in = new FileInputStream(configJson)) {
                    String existing = IoUtil.readAll(in, "UTF-8").trim();
                    if (!existing.isEmpty()) {
                        root = new JSONObject(existing);
                    }
                } catch (Exception ignored) {
                    root = new JSONObject();
                }
            }
            boolean modified = false;

            File craftTargetDir = new File(pluginsDir, "craft-anything");
            File legacyBundledMarker = new File(craftTargetDir, ".bundled_version");
            if (legacyBundledMarker.isFile()) {
                JSONObject plugins = root.optJSONObject("plugins");
                JSONObject craftEntry = plugins != null ? plugins.optJSONObject("craft-anything") : null;
                boolean userEnabledOrInstalledFromMp = craftEntry != null
                        && (craftEntry.optBoolean("enabled", false) || craftEntry.has("installedFrom"));
                if (!userEnabledOrInstalledFromMp) {
                    deleteRecursive(craftTargetDir);
                    new File(runtimeBin, "craft-anything-cli").delete();
                    new File(runtimeBin, "craft-ghidra-setup").delete();
                    if (plugins != null && plugins.has("craft-anything")) {
                        plugins.remove("craft-anything");
                        modified = true;
                    }
                    Log.i(TAG, "removed legacy disabled bundled craft-anything stub (now managed via Marketplace)");
                } else {
                    legacyBundledMarker.delete();
                    if (craftEntry != null && !craftEntry.has("installedFrom")) {
                        JSONObject from = new JSONObject();
                        from.put("id", "antigravity-plugins-official/plugin/craft-anything");
                        from.put("marketplace", "antigravity-plugins-official");
                        from.put("version", "0.4.0-r8");
                        craftEntry.put("installedFrom", from);
                        modified = true;
                    }
                }
            }

            JSONObject userSettings = root.optJSONObject("userSettings");
            if (userSettings == null) {
                userSettings = new JSONObject();
                root.put("userSettings", userSettings);
                modified = true;
            }
            if (!userSettings.has("enableTerminalSandbox") || userSettings.optBoolean("enableTerminalSandbox", true)) {
                userSettings.put("enableTerminalSandbox", false);
                modified = true;
            }
            if (!userSettings.has("sandboxEnabledAtV2Migration") || userSettings.optBoolean("sandboxEnabledAtV2Migration", true)) {
                userSettings.put("sandboxEnabledAtV2Migration", false);
                modified = true;
            }
            if (!userSettings.has("nonWorkspaceFileAccessPolicy")) {
                userSettings.put("nonWorkspaceFileAccessPolicy", "AGENT_SETTING_POLICY_ALLOW");
                modified = true;
            }
            if (!userSettings.has("permissionGrantsV2Migrated")) {
                userSettings.put("permissionGrantsV2Migrated", true);
                modified = true;
            }
            JSONObject grants = userSettings.optJSONObject("globalPermissionGrants");
            if (grants == null) {
                grants = new JSONObject();
                JSONArray allow = new JSONArray();
                allow.put("mcp(mcp(*))");
                grants.put("allow", allow);
                userSettings.put("globalPermissionGrants", grants);
                modified = true;
            }

            if (modified) {
                AtomicFileWriter.writeText(configJson, root.toString(2) + "\n");
                Log.i(TAG, "updated " + configJson + " (Android sandbox/MCP settings)");
            }
        } catch (Exception e) {
            Log.w(TAG, "seedAndroidRuntimeAndConfig notice: " + e.getMessage());
        }
    }

    private static void seedStateFile(Context appContext, String assetName, File target)
            throws Exception {
        if (target.isFile() && target.length() > 0) {
            if (assetName.endsWith(".pbtxt")) {
                try (InputStream existingIn = new FileInputStream(target)) {
                    String existing = IoUtil.readAll(existingIn, "UTF-8");
                    if (!existing.contains("user_config_migrated:") || !existing.contains("enable_terminal_sandbox:")) {
                        StringBuilder sb = new StringBuilder(existing);
                        if (!existing.endsWith("\n")) {
                            sb.append("\n");
                        }
                        if (!existing.contains("user_config_migrated:")) {
                            sb.append("user_config_migrated: true\n");
                        }
                        if (!existing.contains("enable_terminal_sandbox:")) {
                            sb.append("# user_settings: { enable_terminal_sandbox: false }\n");
                        }
                        AtomicFileWriter.writeText(target, sb.toString());
                        Log.i(TAG, "updated existing state with Android sandbox migration: " + target);
                    }
                } catch (Exception ignored) {
                }
            } else if ("AGENTS.md".equals(assetName)) {
                try (InputStream existingIn = new FileInputStream(target)) {
                    String existing = IoUtil.readAll(existingIn, "UTF-8");
                    String versionTag = "(v" + RuntimeConfig.CORE_VERSION + ")";
                    boolean outdatedBundledGuide = existing.contains("Antigravity Android")
                            && !existing.contains(versionTag);
                    if (!existing.contains("mcp_browser_page_snapshot") || outdatedBundledGuide) {
                        try (InputStream seedIn = appContext.getAssets().open(
                                RuntimePaths.SEED_ASSET + "/" + assetName)) {
                            String fresh = IoUtil.readAll(seedIn, "UTF-8");
                            AtomicFileWriter.writeText(target, fresh);
                            Log.i(TAG, "upgraded AGENTS.md to " + versionTag + ": " + target);
                        }
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            Log.i(TAG, "state present; left untouched: " + target);
            return;
        }
        try (InputStream in = appContext.getAssets().open(
                RuntimePaths.SEED_ASSET + "/" + assetName)) {
            String content = IoUtil.readAll(in, "UTF-8");
            AtomicFileWriter.writeText(target, content);
        }
        Log.i(TAG, "seeded onboarding state (was missing): " + target);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
