package com.agy.runtime;

import android.util.Log;

import com.agy.install.EnvInstaller;
import com.agy.util.ShellQuote;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

/**
 * Materializes the bionic bridges in getFilesDir()/runtime/bin. Must run after
 * EnvInstaller.install() (it provides $PREFIX/bin/bash). Idempotent; only tools
 * that actually exist at runtime get a bridge (missing tools are skipped).
 *
 * The external-URL openers (xdg-open/open_helper/sensible-browser/termux-open)
 * write to both stdout ("AGY_OPEN_URL=") and $HOME/.open_url_queue so OpenUrlWatcher
 * dispatches the browser reliably even inside PTY/run_command subshells.
 */
public final class ShellBridgeInstaller {

    private static final String TAG = "ShellBridgeInstaller";

    private static final String[] OPEN_URL_SHIMS = {
            "xdg-open", "open", "open_helper", "sensible-browser",
            "x-www-browser", "gnome-open", "termux-open", "termux-open-url"
    };

    private static final String[] BROWSER_SHIMS = {
            "google-chrome", "chrome", "chromium", "chromium-browser"
    };

    private static final String[] ENCODER_SHIMS = {
            "webp_encoder", "webm_encoder", "webm_encoder.elf", "agy-node"
    };

    /** Known file offset of embedded ELF #2 (webm_encoder / webp_encoder) in liblanguage_server.so. */
    private static final long KNOWN_ELF2_OFFSET = 0xAE42039L;

    /** Records which prefix the bulk bridges were generated for. Bump when the
     *  bridge script format changes so a warm launch regenerates them once. */
    private static final String BRIDGE_MARKER = ".agy-bridges";
    private static final int BRIDGE_FORMAT = 11;

    private ShellBridgeInstaller() {
    }

    private static String buildLdPreloadSnippet() {
        return "if [ -f \"$PREFIX/lib/libagy-go-dns.so\" ] && [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n"
                + "  export LD_PRELOAD=\"$PREFIX/lib/libagy-go-dns.so:$PREFIX/lib/libtermux-exec.so\"\n"
                + "elif [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n"
                + "  export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so\"\n"
                + "elif [ -f \"$PREFIX/lib/libagy-go-dns.so\" ]; then\n"
                + "  export LD_PRELOAD=\"$PREFIX/lib/libagy-go-dns.so\"\n"
                + "else\n"
                + "  unset LD_PRELOAD\n"
                + "fi\n";
    }

    public static synchronized void installFallbackBridges(File bin) {
        installFallbackBridges(bin, null);
    }

    public static synchronized void installFallbackBridges(File bin, android.content.Context context) {
        if (bin == null) return;
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            Log.w(TAG, "cannot create " + bin + "; skipping fallback bridges");
            return;
        }
        String fallbackShell = "#!/system/bin/sh\n"
                + "exec /system/bin/sh \"$@\"\n";
        // Always guarantee bash and sh exist immediately
        File bash = new File(bin, "bash");
        if (!bash.isFile()) {
            writeScript(bash, fallbackShell);
        }
        File sh = new File(bin, "sh");
        if (!sh.isFile()) {
            writeScript(sh, fallbackShell);
        }
        writeAgyNodeShim(bin, context);
        writeTorchShims(bin, context);
        installEmbeddedEncoder(bin, context);
    }

    private static void writeAgyNodeShim(File bin, android.content.Context context) {
        File filesDir = (context != null) ? context.getFilesDir() : bin.getParentFile().getParentFile();
        File prefix = new File(filesDir, "usr");
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        String h = ShellQuote.quote(filesDir.getAbsolutePath());
        String rbin = bin.getAbsolutePath();
        String script = "#!/system/bin/sh\n"
                + "# AGY node wrapper for plugins invoking agy-node\n"
                + "PREFIX=" + p + "\n"
                + "export PREFIX\n"
                + "export HOME=" + h + "\n"
                + "export ANTIGRAVITY_APP_DATA_DIR=\"$HOME/.gemini/antigravity-app\"\n"
                + "export TMPDIR=\"$PREFIX/tmp\"\n"
                + buildLdPreloadSnippet()
                + "export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n"
                + "export PATH=\"$PREFIX/bin:" + rbin + ":$HOME/.gemini/antigravity-app/bin:$HOME/.local/bin:/system/bin\"\n"
                + "export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n"
                + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                + "export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n"
                + "if [ -x \"$PREFIX/bin/node\" ]; then\n"
                + "  exec \"$PREFIX/bin/node\" \"$@\"\n"
                + "fi\n"
                + "echo \"agy-node: nodejs is not installed in Android userspace (run: pkg install nodejs)\" >&2\n"
                + "exit 127\n";
        writeScript(new File(bin, "agy-node"), script);
    }

    private static void ensureGoDnsPreloadLib(android.content.Context context, File prefix) {
        if (context == null || prefix == null) return;
        File libDir = new File(prefix, "lib");
        if (!libDir.isDirectory() && !libDir.mkdirs()) return;
        File dst = new File(libDir, "libagy-go-dns.so");
        try (InputStream in = context.getAssets().open("env/overlay/lib/libagy-go-dns.so")) {
            byte[] buf = new byte[16384];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            byte[] raw = bos.toByteArray();
            if (raw.length > 0 && (!dst.isFile() || dst.length() != raw.length)) {
                File tmp = new File(libDir, "libagy-go-dns.so.tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(raw);
                }
                tmp.setReadable(true, false);
                tmp.setExecutable(true, false);
                try {
                    android.system.Os.chmod(tmp.getAbsolutePath(), 0755);
                } catch (Throwable ignored) {
                }
                if (!tmp.renameTo(dst)) {
                    dst.delete();
                    tmp.renameTo(dst);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureGoDnsPreloadLib notice: " + t.getMessage());
        }
    }

    /**
     * Extracts embedded ELF #2 (webm_encoder / webp_encoder) from liblanguage_server.so
     * into runtime/bin/webm_encoder.elf (already patched by patches/groups/{syscalls,sigill,resolv})
     * and installs glibc libloader.so wrapper scripts at runtime/bin/webp_encoder
     * (intercepted by mcp.findSiblingBinary) and runtime/bin/webm_encoder.
     */
    public static synchronized void installEmbeddedEncoder(File bin, android.content.Context context) {
        if (bin == null) return;
        File core = new File(bin, RuntimePaths.CORE_NAME);
        File targetElf = new File(bin, "webm_encoder.elf");
        if ((!targetElf.isFile() || targetElf.length() < 65536) && core.isFile()) {
            try (RandomAccessFile raf = new RandomAccessFile(core, "r")) {
                long elfOffset = findEmbeddedEncoderOffset(raf);
                if (elfOffset > 0) {
                    long elfSize = computeElf64Size(raf, elfOffset);
                    if (elfSize > 0 && (!targetElf.isFile() || targetElf.length() != elfSize)) {
                        extractRangeToFile(raf, elfOffset, elfSize, targetElf);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "cannot extract embedded webm_encoder.elf: " + e.getMessage());
            }
        }
        File runtimeDir = bin.getParentFile();
        File libDir = runtimeDir != null ? new File(runtimeDir, "lib") : new File(bin, "../lib");
        String rbin = ShellQuote.quote(bin.getAbsolutePath());
        String rlib = ShellQuote.quote(libDir.getAbsolutePath());
        String wrapper = "#!/system/bin/sh\n"
                + "# AGY glibc wrapper for embedded ELF #2 (webp_encoder / webm_encoder)\n"
                + "unset LD_PRELOAD\n"
                + "unset LD_LIBRARY_PATH\n"
                + "RBIN=" + rbin + "\n"
                + "RLIB=" + rlib + "\n"
                + "LOADER=\"$RBIN/libloader.so\"\n"
                + "PRELOAD=\"$RLIB/liblse_compat.so\"\n"
                + "ELF_BIN=\"$RBIN/webm_encoder.elf\"\n"
                + "if [ -f \"$PRELOAD\" ]; then\n"
                + "  exec \"$LOADER\" --library-path \"$RLIB\" --preload \"$PRELOAD\" \"$ELF_BIN\" \"$@\"\n"
                + "else\n"
                + "  exec \"$LOADER\" --library-path \"$RLIB\" \"$ELF_BIN\" \"$@\"\n"
                + "fi\n";
        writeScript(new File(bin, "webp_encoder"), wrapper);
        writeScript(new File(bin, "webm_encoder"), wrapper);
        if (context != null) {
            for (String rel : new String[]{".gemini/antigravity-app/bin", "home/.gemini/antigravity-app/bin"}) {
                File appBin = new File(context.getFilesDir(), rel);
                if (appBin.isDirectory() || appBin.mkdirs()) {
                    writeScript(new File(appBin, "webm_encoder"), wrapper);
                }
            }
        }
    }

    private static long findEmbeddedEncoderOffset(RandomAccessFile raf) throws IOException {
        long len = raf.length();
        if (len > KNOWN_ELF2_OFFSET + 64 && isValidAarch64Elf64(raf, KNOWN_ELF2_OFFSET)) {
            return KNOWN_ELF2_OFFSET;
        }
        // Fallback scan over the tail 32 MB if liblanguage_server.so offset shifted in a newer build
        long start = Math.max(4096L, len - 32L * 1024L * 1024L);
        raf.seek(start);
        byte[] buf = new byte[65536];
        long pos = start;
        long lastFound = -1L;
        while (pos < len - 64) {
            int toRead = (int) Math.min(buf.length, len - pos);
            int n = raf.read(buf, 0, toRead);
            if (n < 8) {
                break;
            }
            for (int i = 0; i <= n - 8; i++) {
                if (buf[i] == 0x7F && buf[i + 1] == 'E' && buf[i + 2] == 'L' && buf[i + 3] == 'F'
                        && buf[i + 4] == 2 && buf[i + 5] == 1 && buf[i + 6] == 1) {
                    long cand = pos + i;
                    if (isValidAarch64Elf64(raf, cand)) {
                        lastFound = cand;
                    }
                    raf.seek(pos + n);
                }
            }
            pos += n - 7;
            raf.seek(pos);
        }
        return lastFound;
    }

    private static boolean isValidAarch64Elf64(RandomAccessFile raf, long offset) throws IOException {
        if (offset <= 0 || offset + 64 > raf.length()) {
            return false;
        }
        byte[] hdr = new byte[64];
        raf.seek(offset);
        raf.readFully(hdr);
        if (hdr[0] != 0x7F || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F'
                || hdr[4] != 2 || hdr[5] != 1 || hdr[6] != 1) {
            return false;
        }
        ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        int eType = bb.getShort(0x10) & 0xFFFF;
        int eMachine = bb.getShort(0x12) & 0xFFFF;
        int eShentsize = bb.getShort(0x3A) & 0xFFFF;
        int eShnum = bb.getShort(0x3C) & 0xFFFF;
        return (eType == 2 || eType == 3) && eMachine == 0xB7 && eShentsize == 64 && eShnum > 0 && eShnum < 256;
    }

    private static long computeElf64Size(RandomAccessFile raf, long elfOffset) throws IOException {
        byte[] hdr = new byte[64];
        raf.seek(elfOffset);
        raf.readFully(hdr);
        ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        long eShoff = bb.getLong(0x28);
        int eShentsize = bb.getShort(0x3A) & 0xFFFF;
        int eShnum = bb.getShort(0x3C) & 0xFFFF;
        long tableEnd = eShoff + (long) eShnum * eShentsize;
        if (eShoff <= 0 || eShentsize != 64 || eShnum <= 0 || elfOffset + tableEnd > raf.length()) {
            return -1L;
        }
        long maxEnd = tableEnd;
        byte[] shdrs = new byte[eShnum * 64];
        raf.seek(elfOffset + eShoff);
        raf.readFully(shdrs);
        ByteBuffer sbb = ByteBuffer.wrap(shdrs).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < eShnum; i++) {
            int base = i * 64;
            int shType = sbb.getInt(base + 0x04);
            long shOffset = sbb.getLong(base + 0x18);
            long shSize = sbb.getLong(base + 0x20);
            if (shType != 8 && shOffset > 0 && shSize > 0) { // 8 == SHT_NOBITS (.bss)
                long end = shOffset + shSize;
                if (end > maxEnd) {
                    maxEnd = end;
                }
            }
        }
        return (elfOffset + maxEnd <= raf.length()) ? maxEnd : -1L;
    }

    private static void extractRangeToFile(RandomAccessFile raf, long offset, long size, File target) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        raf.seek(offset);
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            long remaining = size;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = raf.read(buf, 0, toRead);
                if (n <= 0) {
                    throw new IOException("unexpected EOF extracting embedded ELF");
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
        tmp.setReadable(true, false);
        tmp.setExecutable(true, false);
        try {
            android.system.Os.chmod(tmp.getAbsolutePath(), 0755);
        } catch (Throwable ignored) {
        }
        if (!tmp.renameTo(target)) {
            if (target.exists() && target.delete() && tmp.renameTo(target)) {
                return;
            }
            throw new IOException("cannot rename " + tmp + " to " + target);
        }
    }

    public static synchronized void writeTorchShims(File bin, android.content.Context context) {
        String pkg = (context != null) ? context.getPackageName() : "com.agy";
        String script = "#!/system/bin/sh\n"
                + "# AGY Torch / Flashlight shim\n"
                + "STATE=\"$1\"\n"
                + "[ -z \"$STATE\" ] && STATE=\"on\"\n"
                + "ENABLE=\"true\"\n"
                + "if [ \"$STATE\" = \"off\" ] || [ \"$STATE\" = \"0\" ] || [ \"$STATE\" = \"false\" ]; then\n"
                + "  ENABLE=\"false\"\n"
                + "fi\n"
                + "if [ -x /system/bin/cmd ]; then\n"
                + "  /system/bin/cmd camera set-torch-mode 0 \"$STATE\" >/dev/null 2>&1 || true\n"
                + "fi\n"
                + "if [ -x /system/bin/am ]; then\n"
                + "  /system/bin/am broadcast -a com.agy.ACTION_TORCH -p " + pkg + " --ez enabled \"$ENABLE\" --es state \"$STATE\" >/dev/null 2>&1 || true\n"
                + "fi\n"
                + "echo \"Flashlight set to $STATE\"\n"
                + "exit 0\n";
        writeScript(new File(bin, "torch"), script);
        writeScript(new File(bin, "termux-torch"), script);
        writeScript(new File(bin, "termux-camera-torch"), script);
    }

    public static synchronized void installOpenUrlShims(File bin) {
        installOpenUrlShims(bin, null);
    }

    public static synchronized void installOpenUrlShims(File bin, android.content.Context context) {
        if (bin == null) return;
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            Log.w(TAG, "cannot create " + bin + "; skipping open-url shims");
            return;
        }
        for (String opener : OPEN_URL_SHIMS) {
            writeOpenUrlShim(new File(bin, opener), context);
        }
        for (String browser : BROWSER_SHIMS) {
            writeBrowserShim(new File(bin, browser), context);
        }
        if (context != null) {
            File prefixBin = new File(context.getFilesDir(), "usr/bin");
            if (prefixBin.isDirectory()) {
                for (String opener : OPEN_URL_SHIMS) {
                    File pf = new File(prefixBin, opener);
                    pf.delete();
                    writeOpenUrlShim(pf, context);
                }
            }
        }
    }

    public static synchronized void install(RuntimeManager rm) {
        install(rm, false);
    }

    /**
     * Materializes the bionic bridges. The bulk pass (one script per $PREFIX/bin
     * tool, ~400 files) only runs when the marker is missing/stale or {@code force}
     * is set (fresh env install); a warm launch just refreshes the cheap critical
     * shims.
     */
    public static synchronized void install(RuntimeManager rm, boolean force) {
        File bin = rm.getBinDir();
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            Log.w(TAG, "cannot create " + bin + "; skipping shell bridges");
            return;
        }
        File prefix = EnvInstaller.prefix(rm.appContext());
        ensureGoDnsPreloadLib(rm.appContext(), prefix);

        // Always ensure fallback shells and shims exist FIRST
        installFallbackBridges(bin, rm.appContext());
        installOpenUrlShims(bin, rm.appContext());

        File workspaceDir = new File(prefix.getParentFile(), "workspace");
        if (!workspaceDir.isDirectory()) {
            workspaceDir.mkdirs();
        }
        File prefixBash = new File(prefix, "bin/bash");
        if (!prefixBash.isFile()) {
            Log.w(TAG, "prefix bash missing (" + prefixBash + "); keeping fallback shell bridges");
            return;
        }
        writeAgyBashrc(prefix, bin);
        patchTermuxBinaryPrefixInPlace(prefixBash);
        patchTermuxBinaryPrefixInPlace(new File(prefix, "lib/libtermux-exec.so"));
        writeBridge(new File(bin, "bash"), prefix, "bash");
        writeBridge(new File(bin, "sh"), prefix, "bash");
        writeNpmShims(bin, prefix);
        File[] prefixTools = new File(prefix, "bin").listFiles();
        int toolCount = countFiles(prefixTools);
        File marker = new File(bin, BRIDGE_MARKER);
        if (!force && markerMatches(marker, prefix, toolCount)) {
            return;
        }
        if (prefixTools != null) {
            for (File toolFile : prefixTools) {
                if (toolFile.isFile()) {
                    String name = toolFile.getName();
                    if ("rish".equals(name) || isOpenUrlShim(name)) {
                        continue;
                    }
                    patchTermuxBinaryPrefixInPlace(toolFile);
                    writeBridge(new File(bin, name), prefix, name);
                }
            }
        }
        File[] prefixLibs = new File(prefix, "lib").listFiles();
        if (prefixLibs != null) {
            for (File libFile : prefixLibs) {
                if (libFile.isFile() && libFile.getName().contains(".so")
                        && !"libagy-go-dns.so".equals(libFile.getName())) {
                    patchTermuxBinaryPrefixInPlace(libFile);
                }
            }
        }
        writeMarker(marker, prefix, toolCount);
    }

    private static int countFiles(File[] files) {
        int n = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean markerMatches(File marker, File prefix, int toolCount) {
        if (!marker.isFile()) {
            return false;
        }
        try (InputStream in = new FileInputStream(marker)) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            String content = n > 0 ? new String(buf, 0, n, Charset.forName("UTF-8")) : "";
            return content.equals(markerContent(prefix, toolCount));
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeMarker(File marker, File prefix, int toolCount) {
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(markerContent(prefix, toolCount).getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            Log.w(TAG, "cannot write bridge marker " + marker + ": " + e.getMessage());
        }
    }

    /** Version + prefix + tool count: any of these changing forces a rebuild. */
    private static String markerContent(File prefix, int toolCount) {
        return BRIDGE_FORMAT + "\n" + prefix.getAbsolutePath() + "\n" + toolCount + "\n";
    }

    private static boolean isOpenUrlShim(String name) {
        for (String opener : OPEN_URL_SHIMS) {
            if (opener.equals(name)) {
                return true;
            }
        }
        for (String browser : BROWSER_SHIMS) {
            if (browser.equals(name)) {
                return true;
            }
        }
        for (String encoder : ENCODER_SHIMS) {
            if (encoder.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the AGY URL opener shim. Prints "AGY_OPEN_URL=<url>", appends to
     * $HOME/.open_url_queue (watched by OpenUrlWatcher for PTY/run_command shells),
     * and attempts direct broadcast/intent dispatch via /system/bin/am.
     */
    private static void writeOpenUrlShim(File bridge, android.content.Context context) {
        String pkg = (context != null) ? context.getPackageName() : "com.agy";
        File filesDir = (context != null) ? context.getFilesDir() : bridge.getParentFile().getParentFile();
        String queuePath = ShellQuote.quote(new File(filesDir, ".open_url_queue").getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# AGY URL opener shim (xdg-open / open / termux-open / sensible-browser).\n");
        sb.append("unset LD_PRELOAD\n");
        sb.append("unset LD_LIBRARY_PATH\n");
        sb.append("URL=\"\"\n");
        sb.append("for arg in \"$@\"; do\n");
        sb.append("  case \"$arg\" in\n");
        sb.append("    -*) ;;\n");
        sb.append("    *) URL=\"$arg\" ;;\n");
        sb.append("  esac\n");
        sb.append("done\n");
        sb.append("[ -z \"$URL\" ] && URL=\"$1\"\n");
        sb.append("[ -z \"$URL\" ] && exit 0\n");
        sb.append("echo \"AGY_OPEN_URL=$URL\"\n");
        sb.append("echo \"ANTIGRAVITY_OPEN_URL: $URL\"\n");
        sb.append("printf '%s\\n' \"$URL\" >> ").append(queuePath).append(" 2>/dev/null || true\n");
        sb.append("if [ -x /system/bin/am ]; then\n");
        sb.append("  /system/bin/am broadcast -a com.agy.ACTION_OPEN_URL -p ").append(pkg).append(" --es url \"$URL\" >/dev/null 2>&1 || true\n");
        if (!"com.agy".equals(pkg)) {
            sb.append("  /system/bin/am broadcast -a com.agy.ACTION_OPEN_URL -p com.agy --es url \"$URL\" >/dev/null 2>&1 || true\n");
        }
        sb.append("  /system/bin/am start -a android.intent.action.VIEW -d \"$URL\" >/dev/null 2>&1 || true\n");
        sb.append("fi\n");
        sb.append("exit 0\n");
        writeScript(bridge, sb.toString());
    }

    /**
     * Writes the smart browser shim (google-chrome / chrome / chromium).
     * When invoked with headless/CDP/CLI flags and a real Chromium binary is
     * installed in $PREFIX/bin, it sets up the bionic environment and execs it
     * with Android-compatible headless flags (--no-sandbox --disable-gpu
     * --disable-dev-shm-usage). If headless was explicitly requested and Chromium
     * is missing, fails cleanly with exit 127 and an actionable pkg hint.
     */
    private static void writeBrowserShim(File bridge, android.content.Context context) {
        File filesDir = (context != null) ? context.getFilesDir() : bridge.getParentFile().getParentFile();
        File prefix = new File(filesDir, "usr");
        File runtimeBinDir = bridge.getParentFile();
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        String h = ShellQuote.quote(filesDir.getAbsolutePath());
        String rbin = runtimeBinDir != null ? runtimeBinDir.getAbsolutePath() : "";
        String xdgOpenPath = ShellQuote.quote(new File(runtimeBinDir, "xdg-open").getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# AGY smart browser shim: supports both headless/CDP Chromium and OAuth URL opening.\n");
        sb.append("PREFIX=").append(p).append('\n');
        sb.append("HOME=").append(h).append('\n');
        sb.append("WANT_HEADLESS=0\n");
        sb.append("for arg in \"$@\"; do\n");
        sb.append("  case \"$arg\" in\n");
        sb.append("    --headless*|--remote-debugging*|--user-data-dir*|--dump-dom*|--screenshot*|--version|--product-version)\n");
        sb.append("      WANT_HEADLESS=1\n");
        sb.append("      break\n");
        sb.append("      ;;\n");
        sb.append("  esac\n");
        sb.append("done\n");
        sb.append("if [ \"$WANT_HEADLESS\" = \"1\" ]; then\n");
        sb.append("  REAL_CHROME=\"\"\n");
        sb.append("  for cand in \"$PREFIX/bin/chromium\" \"$PREFIX/bin/chromium-browser\" \"$PREFIX/bin/google-chrome\"; do\n");
        sb.append("    if [ -x \"$cand\" ]; then\n");
        sb.append("      REAL_CHROME=\"$cand\"\n");
        sb.append("      break\n");
        sb.append("    fi\n");
        sb.append("  done\n");
        sb.append("  if [ -n \"$REAL_CHROME\" ]; then\n");
        sb.append("    export PREFIX HOME\n");
        sb.append(buildLdPreloadSnippet());
        sb.append("    export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("    export PATH=\"$PREFIX/bin:").append(rbin).append(":$HOME/.gemini/antigravity-app/bin:$HOME/.local/bin:/system/bin\"\n");
        sb.append("    export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n");
        sb.append("    export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("    export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("    exec \"$REAL_CHROME\" --no-sandbox --disable-gpu --disable-dev-shm-usage \"$@\"\n");
        sb.append("  fi\n");
        sb.append("  echo \"chrome: headless/CDP requested but Chromium is not installed in Android userspace. Run: pkg update && pkg install chromium\" >&2\n");
        sb.append("  exit 127\n");
        sb.append("fi\n");
        sb.append("exec ").append(xdgOpenPath).append(" \"$@\"\n");
        writeScript(bridge, sb.toString());
    }

    private static void writeAgyBashrc(File prefix, File runtimeBinDir) {
        try {
            File etcDir = new File(prefix, "etc");
            if (!etcDir.isDirectory()) etcDir.mkdirs();
            File tmpDir = new File(prefix, "tmp");
            if (!tmpDir.isDirectory()) tmpDir.mkdirs();
            String p = ShellQuote.quote(prefix.getAbsolutePath());
            String rbin = runtimeBinDir != null ? runtimeBinDir.getAbsolutePath() : "";
            String chromePath = runtimeBinDir != null
                    ? ShellQuote.quote(new File(runtimeBinDir, "chrome").getAbsolutePath()) : "\"chrome\"";
            String rc = "# Antigravity Terminal Environment\n"
                    + "export PREFIX=" + p + "\n"
                    + "export ANTIGRAVITY_APP_DATA_DIR=\"$HOME/.gemini/antigravity-app\"\n"
                    + "export TMPDIR=\"$PREFIX/tmp\"\n"
                    + "export TERM=\"xterm-256color\"\n"
                    + "export LANG=\"C.UTF-8\"\n"
                    + "export LC_ALL=\"C.UTF-8\"\n"
                    + "export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n"
                    + buildLdPreloadSnippet()
                    + "export PATH=\"$PREFIX/bin:" + rbin + ":$HOME/.gemini/antigravity-app/bin:$HOME/.local/bin:/system/bin\"\n"
                    + "export CHROME_PATH=" + chromePath + "\n"
                    + "export PUPPETEER_EXECUTABLE_PATH=" + chromePath + "\n"
                    + "export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n"
                    + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                    + "export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n"
                    + "export JDK_JAVA_OPTIONS=\"-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp\"\n"
                    + "PS1='\\[\\033[01;32m\\]agy\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '\n";
            writeScript(new File(etcDir, "agy.bashrc"), rc);
            writeScript(new File(etcDir, "profile"), rc);
            writeScript(new File(etcDir, "bash.bashrc"), rc);
            File homeDir = prefix.getParentFile();
            if (homeDir != null && homeDir.isDirectory()) {
                writeScript(new File(homeDir, ".bashrc"), rc);
                writeScript(new File(homeDir, ".profile"), rc);
                File ws = new File(homeDir, "workspace");
                if (ws.isDirectory()) {
                    writeScript(new File(ws, ".bashrc"), rc);
                    writeScript(new File(ws, ".profile"), rc);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "writeAgyBashrc notice: " + t.getMessage());
        }
    }

    /**
     * Replaces the 31-byte ASCII sequence "/data/data/com.termux/files/usr" with
     * the exact same length 31-byte sequence "/data/data/com.agy////files/usr"
     * inside ELF binaries (bash, libtermux-exec.so, etc.) using an atomic temp-file swap.
     */
    private static void patchTermuxBinaryPrefixInPlace(File file) {
        if (file == null || !file.isFile()) return;
        long len = file.length();
        if (len < 64 || len > 16 * 1024 * 1024) return;
        try {
            byte[] data = new byte[(int) len];
            try (InputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
            // Only patch ELF binaries (0x7F 'E' 'L' 'F')
            if (data[0] != 0x7F || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
                return;
            }
            byte[] needle = "/data/data/com.termux/files/usr".getBytes(Charset.forName("US-ASCII"));
            byte[] replacement = "/data/data/com.agy////files/usr".getBytes(Charset.forName("US-ASCII"));
            if (needle.length != replacement.length) return;

            boolean modified = false;
            int limit = data.length - needle.length;
            for (int i = 0; i <= limit; i++) {
                if (data[i] == needle[0] && data[i + 11] == 'c' && data[i + 15] == 't') {
                    boolean match = true;
                    for (int j = 0; j < needle.length; j++) {
                        if (data[i + j] != needle[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        System.arraycopy(replacement, 0, data, i, replacement.length);
                        modified = true;
                        i += needle.length - 1;
                    }
                }
            }
            if (!modified) return;

            File tmp = new File(file.getParentFile(), file.getName() + ".agytmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            tmp.setReadable(true, false);
            tmp.setExecutable(true, false);
            try {
                android.system.Os.chmod(tmp.getAbsolutePath(), 0755);
            } catch (Throwable ignored) {
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Throwable t) {
            Log.w(TAG, "patchTermuxBinaryPrefixInPlace notice for " + file.getName() + ": " + t.getMessage());
        }
    }

    private static void writeNpmShims(File runtimeBinDir, File prefix) {
        try {
            File prefixBin = new File(prefix, "bin");
            if (!prefixBin.isDirectory()) prefixBin.mkdirs();
            String p = ShellQuote.quote(prefix.getAbsolutePath());
            File filesDir = prefix.getParentFile();
            String h = ShellQuote.quote(filesDir != null ? filesDir.getAbsolutePath() : prefix.getAbsolutePath());
            String rbin = runtimeBinDir != null ? runtimeBinDir.getAbsolutePath() : "";
            String chromePath = runtimeBinDir != null
                    ? ShellQuote.quote(new File(runtimeBinDir, "chrome").getAbsolutePath()) : "\"chrome\"";

            for (String tool : new String[]{"npm", "npx"}) {
                String cliJs = "npm".equals(tool) ? "npm-cli.js" : "npx-cli.js";
                String script = "#!/system/bin/sh\n"
                        + "# AGY native " + tool + " shim\n"
                        + "PREFIX=" + p + "\n"
                        + "export PREFIX\n"
                        + "export HOME=" + h + "\n"
                        + "export ANTIGRAVITY_APP_DATA_DIR=\"$HOME/.gemini/antigravity-app\"\n"
                        + "export TMPDIR=\"$PREFIX/tmp\"\n"
                        + buildLdPreloadSnippet()
                        + "export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n"
                        + "export PATH=\"$PREFIX/bin:" + rbin + ":$HOME/.gemini/antigravity-app/bin:$HOME/.local/bin:/system/bin\"\n"
                        + "export CHROME_PATH=" + chromePath + "\n"
                        + "export PUPPETEER_EXECUTABLE_PATH=" + chromePath + "\n"
                        + "export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n"
                        + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                        + "export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n"
                        + "NPM_CLI=\"$PREFIX/lib/node_modules/npm/bin/" + cliJs + "\"\n"
                        + "if [ ! -f \"$NPM_CLI\" ] && [ -x \"$PREFIX/bin/node\" ] && [ -x \"$PREFIX/bin/curl\" ]; then\n"
                        + "  \"$PREFIX/bin/mkdir\" -p \"$PREFIX/lib/node_modules/npm\" \"$PREFIX/tmp\" 2>/dev/null || true\n"
                        + "  \"$PREFIX/bin/curl\" -fsSL --cacert \"$PREFIX/etc/tls/cert.pem\" \"https://registry.npmjs.org/npm/-/npm-10.9.2.tgz\" -o \"$PREFIX/tmp/npm.tgz\" 2>/dev/null \\\n"
                        + "    && \"$PREFIX/bin/tar\" -xzf \"$PREFIX/tmp/npm.tgz\" -C \"$PREFIX/lib/node_modules/npm\" --strip-components=1 2>/dev/null \\\n"
                        + "    && \"$PREFIX/bin/rm\" -f \"$PREFIX/tmp/npm.tgz\" 2>/dev/null || true\n"
                        + "fi\n"
                        + "if [ -f \"$NPM_CLI\" ] && [ -x \"$PREFIX/bin/node\" ]; then\n"
                        + "  exec \"$PREFIX/bin/node\" \"$NPM_CLI\" \"$@\"\n"
                        + "fi\n"
                        + "echo \"npm: node is not installed yet (run: pkg install nodejs)\" >&2\n"
                        + "exit 127\n";
                writeScript(new File(runtimeBinDir, tool), script);
                writeScript(new File(prefixBin, tool), script);
            }
        } catch (Throwable t) {
            Log.w(TAG, "writeNpmShims notice: " + t.getMessage());
        }
    }

    /**
     * Writes one 755 bridge script. It is run by Android's /system/bin/sh (a
     * POSIX shell, no bionic env needed), which then sets PREFIX/LD_LIBRARY_PATH/
     * LD_PRELOAD/PATH for the bionic child only and execs it.
     * IMPORTANT: Never invoke external binaries (like dirname or mkdir) inside this
     * script before PATH is set, as runtime/bin/dirname is itself a bridge script!
     */
    private static void writeBridge(File bridge, File prefix, String target) {
        if ("npm".equals(target) || "npx".equals(target) || "analyzeHeadless".equals(target)
                || "craft-anything-cli".equals(target) || "craft-ghidra-setup".equals(target)
                || "agy-video-mcp".equals(target) || "agy-video-setup".equals(target)) {
            return;
        }
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        File filesDir = prefix.getParentFile();
        String h = ShellQuote.quote(filesDir != null ? filesDir.getAbsolutePath() : prefix.getAbsolutePath());
        File runtimeBinDir = bridge.getParentFile();
        String rbin = runtimeBinDir != null ? runtimeBinDir.getAbsolutePath() : "";
        String chromePath = runtimeBinDir != null
                ? ShellQuote.quote(new File(runtimeBinDir, "chrome").getAbsolutePath()) : "\"chrome\"";
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# AGY bridge -> bionic $PREFIX/bin/").append(target).append('\n');
        sb.append("PREFIX=").append(p).append('\n');
        sb.append("export PREFIX\n");
        sb.append("export HOME=").append(h).append('\n');
        sb.append("export ANTIGRAVITY_APP_DATA_DIR=\"$HOME/.gemini/antigravity-app\"\n");
        sb.append("export TMPDIR=\"$PREFIX/tmp\"\n");
        sb.append("export JDK_JAVA_OPTIONS=\"-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp\"\n");
        sb.append("export TERM=\"xterm-256color\"\n");
        sb.append(buildLdPreloadSnippet());
        sb.append("export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("export PATH=\"$PREFIX/bin:").append(rbin).append(":$HOME/.gemini/antigravity-app/bin:$HOME/.local/bin:/system/bin\"\n");
        sb.append("export CHROME_PATH=").append(chromePath).append('\n');
        sb.append("export PUPPETEER_EXECUTABLE_PATH=").append(chromePath).append('\n');
        sb.append("export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n");
        sb.append("export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n");
        if ("bash".equals(target)) {
            sb.append("if [ -x \"$PREFIX/bin/bash\" ]; then\n");
            sb.append("  _HAS_RC=0\n");
            sb.append("  _HAS_C=0\n");
            sb.append("  for _a in \"$@\"; do\n");
            sb.append("    case \"$_a\" in\n");
            sb.append("      --rcfile*|--init-file*|--norc) _HAS_RC=1 ;;\n");
            sb.append("      -c|-lc|-cl) _HAS_C=1 ;;\n");
            sb.append("    esac\n");
            sb.append("  done\n");
            sb.append("  for _a in \"$@\"; do\n");
            sb.append("    shift\n");
            sb.append("    case \"$_a\" in\n");
            sb.append("      -l|--login) ;;\n");
            sb.append("      -lc|-cl) set -- \"$@\" \"-c\" ;;\n");
            sb.append("      -il|-li) set -- \"$@\" \"-i\" ;;\n");
            sb.append("      *) set -- \"$@\" \"$_a\" ;;\n");
            sb.append("    esac\n");
            sb.append("  done\n");
            sb.append("  if [ \"$_HAS_C\" = \"1\" ] || [ \"$_HAS_RC\" = \"1\" ]; then\n");
            sb.append("    exec \"$PREFIX/bin/bash\" --noprofile \"$@\"\n");
            sb.append("  else\n");
            sb.append("    exec \"$PREFIX/bin/bash\" --noprofile --rcfile \"$PREFIX/etc/agy.bashrc\" \"$@\"\n");
            sb.append("  fi\n");
            sb.append("else\n");
            sb.append("  exec /system/bin/sh \"$@\"\n");
            sb.append("fi\n");
        } else {
            sb.append("if [ -x \"$PREFIX/bin/").append(target).append("\" ]; then\n");
            sb.append("  exec \"$PREFIX/bin/").append(target).append("\" \"$@\"\n");
            sb.append("else\n");
            sb.append("  exec /system/bin/sh \"$@\"\n");
            sb.append("fi\n");
        }
        writeScript(bridge, sb.toString());
    }

    private static void writeScript(File bridge, String script) {
        try (OutputStream out = new FileOutputStream(bridge)) {
            out.write(script.getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            Log.w(TAG, "cannot write bridge " + bridge + ": " + e.getMessage());
            return;
        }
        bridge.setReadable(true, false);
        bridge.setExecutable(true, false);
        try {
            android.system.Os.chmod(bridge.getAbsolutePath(), 0755);
        } catch (Throwable ignored) {
        }
    }
}
