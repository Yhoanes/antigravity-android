package com.agy.runtime;

import android.content.Context;
import android.util.Log;

import com.agy.util.AtomicFileWriter;
import com.agy.util.FileModeUtil;
import com.agy.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Materializes a fully-onboarded state before the core starts, so the Welcome
 * wizard is skipped. Idempotent and non-destructive: only writes when the
 * target is absent (or a zero-byte stub).
 */
public final class OnboardingSeeder {

    private static final String TAG = "OnboardingSeeder";
    private static final String CRAFT_PLUGIN_ASSET = "plugins/craft-anything.zip";
    private static final String CRAFT_PLUGIN_VERSION = "0.4.0-r6";

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

            // Seed default agent rules (AGENTS.md) and preinstalled plugins into ~/.gemini/config/
            File configDir = projects.getParentFile();
            if (configDir != null && (configDir.isDirectory() || configDir.mkdirs())) {
                seedStateFile(appContext, "AGENTS.md", new File(configDir, "AGENTS.md"));
                new File(configDir, "plugins").mkdirs();
                new File(configDir, "skills").mkdirs();
                new File(configDir, "agents").mkdirs();
                new File(configDir, "sidecars").mkdirs();
                seedCraftAnythingPlugin(appContext, configDir);
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

    private static void seedCraftAnythingPlugin(Context appContext, File configDir) {
        try {
            File filesDir = appContext.getFilesDir();
            File prefixDir = new File(filesDir, "usr");
            File pluginsDir = new File(configDir, "plugins");
            File targetDir = new File(pluginsDir, "craft-anything");
            File marker = new File(targetDir, ".bundled_version");
            File cliBin = new File(targetDir, "mcp/bin/craft-anything-cli");

            boolean needsExtract = !marker.isFile() || !cliBin.isFile() || cliBin.length() < 1000000L;
            if (!needsExtract) {
                try (InputStream in = new FileInputStream(marker)) {
                    String ver = IoUtil.readAll(in, "UTF-8").trim();
                    if (!CRAFT_PLUGIN_VERSION.equals(ver)) {
                        needsExtract = true;
                    }
                } catch (Exception e) {
                    needsExtract = true;
                }
            }

            if (needsExtract) {
                try (InputStream raw = appContext.getAssets().open(CRAFT_PLUGIN_ASSET);
                     ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw))) {
                    String canonicalRoot = targetDir.getCanonicalPath() + File.separator;
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (name == null || name.isEmpty() || name.contains("..")) {
                            continue;
                        }
                        File outFile = new File(targetDir, name);
                        if (!outFile.getCanonicalPath().startsWith(canonicalRoot)) {
                            continue;
                        }
                        if (entry.isDirectory()) {
                            outFile.mkdirs();
                        } else {
                            File parent = outFile.getParentFile();
                            if (parent != null && !parent.isDirectory()) {
                                parent.mkdirs();
                            }
                            try (OutputStream out = new FileOutputStream(outFile)) {
                                IoUtil.pipe(zis, out);
                            }
                        }
                        zis.closeEntry();
                    }
                    AtomicFileWriter.writeText(marker, CRAFT_PLUGIN_VERSION + "\n");
                    Log.i(TAG, "extracted bundled craft-anything plugin to " + targetDir);
                } catch (Exception e) {
                    Log.w(TAG, "craft-anything plugin asset not present or failed: " + e.getMessage());
                    return;
                }
            }

            // Ensure executable bits on plugin scripts and native ARM64 binaries
            String[] execRelPaths = {
                    "install-plugin.sh",
                    "mcp/bin/start-mcp.sh",
                    "mcp/bin/craft-anything-cli",
                    "mcp/bin/craft-ghidra-setup",
                    "mcp/bin/ghidra-arm64/decompile",
                    "mcp/bin/ghidra-arm64/sleigh"
            };
            for (String rel : execRelPaths) {
                File f = new File(targetDir, rel);
                if (f.isFile()) {
                    FileModeUtil.setExec(f);
                }
            }

            // Stage decompile & sleigh in $PREFIX/share/ghidra-arm64/os/linux_arm_64 and create $PREFIX/tmp/run
            // ahead of time so start-mcp.sh runs in <10ms with zero file copies during MCP handshake.
            File shareNative = new File(prefixDir, "share/ghidra-arm64/os/linux_arm_64");
            if (shareNative.isDirectory() || shareNative.mkdirs()) {
                for (String binName : new String[]{"decompile", "sleigh"}) {
                    File srcBin = new File(targetDir, "mcp/bin/ghidra-arm64/" + binName);
                    File dstBin = new File(shareNative, binName);
                    if (srcBin.isFile() && (!dstBin.isFile() || dstBin.length() != srcBin.length())) {
                        try (InputStream in = new FileInputStream(srcBin);
                             OutputStream out = new FileOutputStream(dstBin)) {
                            IoUtil.pipe(in, out);
                        }
                        FileModeUtil.setExec(dstBin);
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

            // Pre-create ~/.config/craft-anything/config.toml so deep_mode=true and first_run_done=true
            // match --deep from the very first invocation.
            File craftCfgDir = new File(filesDir, ".config/craft-anything");
            if (craftCfgDir.isDirectory() || craftCfgDir.mkdirs()) {
                File craftToml = new File(craftCfgDir, "config.toml");
                if (!craftToml.isFile() || craftToml.length() == 0) {
                    AtomicFileWriter.writeText(craftToml, "deep_mode = true\nfirst_run_done = true\n");
                }
            }

            // Remove any duplicate symlinks in $PREFIX/bin so setup_doctor never warns binary:duplicates,
            // and expose wrappers in $HOME/runtime/bin only.
            File prefixBin = new File(prefixDir, "bin");
            for (String tool : new String[]{"craft-anything-cli", "craft-ghidra-setup"}) {
                File dup = new File(prefixBin, tool);
                if (dup.exists()) {
                    dup.delete();
                }
            }
            File runtimeBin = new File(filesDir, RuntimePaths.BIN_DIR);
            if (runtimeBin.isDirectory() || runtimeBin.mkdirs()) {
                for (String tool : new String[]{"craft-anything-cli", "craft-ghidra-setup"}) {
                    File targetBin = new File(targetDir, "mcp/bin/" + tool);
                    File wrapper = new File(runtimeBin, tool);
                    String script = "#!/system/bin/sh\n"
                            + "export HOME=\"" + filesDir.getAbsolutePath() + "\"\n"
                            + "export PREFIX=\"" + prefixDir.getAbsolutePath() + "\"\n"
                            + "export XDG_CONFIG_HOME=\"" + new File(filesDir, ".config").getAbsolutePath() + "\"\n"
                            + "exec \"" + targetBin.getAbsolutePath() + "\" \"$@\"\n";
                    AtomicFileWriter.writeText(wrapper, script);
                    FileModeUtil.setExec(wrapper);
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
            // 1. Register craft-anything WITHOUT installedFrom (so UI never calls remote marketplace catalog and never shows 'Failed to load plugin').
            // 2. Seed Android-compatible userSettings (enableTerminalSandbox=false so run_command never fails with libloader.so: invalid argument, and pre-grant MCP tools).
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

            JSONObject plugins = root.optJSONObject("plugins");
            if (plugins == null) {
                plugins = new JSONObject();
                root.put("plugins", plugins);
                modified = true;
            }
            JSONObject craftEntry = plugins.optJSONObject("craft-anything");
            if (craftEntry == null) {
                craftEntry = new JSONObject();
                craftEntry.put("enabled", false);
                plugins.put("craft-anything", craftEntry);
                modified = true;
            } else if (craftEntry.has("installedFrom")) {
                craftEntry.remove("installedFrom");
                modified = true;
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
                Log.i(TAG, "updated " + configJson + " (craft-anything clean local entry + Android sandbox/MCP settings)");
            }
        } catch (Exception e) {
            Log.w(TAG, "seedCraftAnythingPlugin notice: " + e.getMessage());
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
                    if (!existing.contains("mcp_browser_page_snapshot")) {
                        try (InputStream seedIn = appContext.getAssets().open(
                                RuntimePaths.SEED_ASSET + "/" + assetName)) {
                            String fresh = IoUtil.readAll(seedIn, "UTF-8");
                            AtomicFileWriter.writeText(target, fresh);
                            Log.i(TAG, "upgraded AGENTS.md with Android browser & plugin rules: " + target);
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
