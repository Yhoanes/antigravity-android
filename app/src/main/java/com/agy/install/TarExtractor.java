package com.agy.install;

import android.system.Os;
import android.util.Log;

import com.agy.util.FileModeUtil;
import com.agy.util.PathSafety;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Pure-Java tar extractor supporting USTAR, PAX extended headers ('x'/'g'),
 * GNU long names/links ('L'/'K'), symbolic links ('2'), and hard links ('1').
 */
public final class TarExtractor {

    private static final String TAG = "TarExtractor";

    private TarExtractor() {
    }

    public static void untarGz(InputStream raw, File dest) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16), 1 << 16)) {
            untar(in, dest);
        }
    }

    public static void untar(InputStream in, File dest) throws IOException {
        byte[] header = new byte[512];
        String pendingName = null;
        String pendingLink = null;
        String paxPath = null;
        String paxLinkPath = null;
        Long paxSize = null;
        String globalPaxPath = null;
        String globalPaxLinkPath = null;

        while (TarHeader.readBlock(in, header)) {
            if (TarHeader.isZeroBlock(header)) {
                break;
            }
            String name = TarHeader.readField(header, 0, 100);
            long mode = TarHeader.readOctal(header, 100, 8);
            long size = TarHeader.readOctal(header, 124, 12);
            int type = header[156] & 0xff;
            String link = TarHeader.readField(header, 157, 100);
            String dirPrefix = TarHeader.readField(header, 345, 155);
            if (type == 0) {
                type = '0';
            }
            if (type == 'L') {
                pendingName = stripTrailingNullsAndNewlines(
                        new String(readEntry(in, size), StandardCharsets.UTF_8));
                continue;
            }
            if (type == 'K') {
                pendingLink = stripTrailingNullsAndNewlines(
                        new String(readEntry(in, size), StandardCharsets.UTF_8));
                continue;
            }
            if (type == 'x' || type == 'g') {
                byte[] paxBytes = readEntry(in, size);
                PaxFields parsed = parsePaxExtendedHeader(paxBytes);
                if (type == 'g') {
                    if (parsed.path != null) globalPaxPath = parsed.path;
                    if (parsed.linkPath != null) globalPaxLinkPath = parsed.linkPath;
                } else {
                    if (parsed.path != null) paxPath = parsed.path;
                    if (parsed.linkPath != null) paxLinkPath = parsed.linkPath;
                    if (parsed.size != null) paxSize = parsed.size;
                }
                continue;
            }

            if (paxSize != null) {
                size = paxSize;
                paxSize = null;
            }

            if (paxPath != null) {
                name = paxPath;
                paxPath = null;
            } else if (pendingName != null) {
                name = pendingName;
                pendingName = null;
            } else if (globalPaxPath != null) {
                name = globalPaxPath;
            } else if (dirPrefix != null && !dirPrefix.isEmpty()) {
                name = dirPrefix + "/" + name;
            }

            if (paxLinkPath != null) {
                link = paxLinkPath;
                paxLinkPath = null;
            } else if (pendingLink != null) {
                link = pendingLink;
                pendingLink = null;
            } else if (globalPaxLinkPath != null) {
                link = globalPaxLinkPath;
            }

            name = TarPath.normalize(name);
            if (name == null || name.isEmpty()) {
                skipEntry(in, size);
                continue;
            }
            File out = new File(dest, name);
            if (!PathSafety.isInside(dest, out)) {
                skipEntry(in, size);
                continue;
            }
            if (type == '5') {
                skipEntry(in, size);
                ensureDirectory(out);
                FileModeUtil.applyMode(out, mode);
            } else if (type == '0') {
                writeFile(in, out, size);
                FileModeUtil.applyMode(out, mode);
            } else if (type == '2') {
                skipEntry(in, size);
                createSymlink(out, link);
            } else if (type == '1') {
                skipEntry(in, size);
                createHardlinkOrCopy(dest, out, link);
            } else {
                skipEntry(in, size);
                Log.w(TAG, "skipped unsupported tar entry " + (char) type + " " + name);
            }
        }
    }

    private static final class PaxFields {
        String path;
        String linkPath;
        Long size;
    }

    private static PaxFields parsePaxExtendedHeader(byte[] data) {
        PaxFields out = new PaxFields();
        int idx = 0;
        int n = data.length;
        while (idx < n) {
            int sp = idx;
            while (sp < n && data[sp] >= '0' && data[sp] <= '9') {
                sp++;
            }
            if (sp == idx || sp >= n || data[sp] != ' ') {
                break;
            }
            int recLen;
            try {
                recLen = Integer.parseInt(new String(data, idx, sp - idx, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (recLen <= (sp - idx + 1) || idx + recLen > n) {
                break;
            }
            int contentStart = sp + 1;
            int contentEnd = idx + recLen;
            if (contentEnd > contentStart && data[contentEnd - 1] == '\n') {
                contentEnd--;
            }
            int eq = contentStart;
            while (eq < contentEnd && data[eq] != '=') {
                eq++;
            }
            if (eq < contentEnd) {
                String key = new String(data, contentStart, eq - contentStart, StandardCharsets.UTF_8);
                String val = new String(data, eq + 1, contentEnd - (eq + 1), StandardCharsets.UTF_8);
                if ("path".equals(key)) {
                    out.path = val;
                } else if ("linkpath".equals(key)) {
                    out.linkPath = val;
                } else if ("size".equals(key)) {
                    try {
                        out.size = Long.parseLong(val.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            idx += recLen;
        }
        return out;
    }

    private static String stripTrailingNullsAndNewlines(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\0' || c == '\n' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (TextFileDetector.isSymlink(dir) || dir.isFile()) {
            unlinkPath(dir);
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create dir " + dir);
        }
    }

    private static void unlinkPath(File target) {
        try {
            Os.remove(target.getAbsolutePath());
            return;
        } catch (Throwable ignored) {
        }
        if (target.isDirectory() && !TextFileDetector.isSymlink(target)) {
            File[] kids = target.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    unlinkPath(k);
                }
            }
        }
        target.delete();
    }

    private static void createSymlink(File out, String linkTarget) throws IOException {
        if (linkTarget == null || linkTarget.isEmpty()) {
            return;
        }
        File parent = out.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        unlinkPath(out);
        try {
            Os.symlink(linkTarget, out.getAbsolutePath());
        } catch (Exception e) {
            throw new IOException("cannot create symlink " + out + " -> " + linkTarget + ": " + e, e);
        }
    }

    private static void createHardlinkOrCopy(File destRoot, File out, String linkTarget) throws IOException {
        if (linkTarget == null || linkTarget.isEmpty()) {
            return;
        }
        String normTarget = TarPath.normalize(linkTarget);
        if (normTarget == null || normTarget.isEmpty()) {
            return;
        }
        File src = new File(destRoot, normTarget);
        if (!PathSafety.isInside(destRoot, src) || !src.exists()) {
            return;
        }
        File parent = out.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        unlinkPath(out);
        try {
            Os.link(src.getAbsolutePath(), out.getAbsolutePath());
            return;
        } catch (Throwable ignored) {
        }
        try (InputStream fis = new FileInputStream(src);
             OutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = fis.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
    }

    private static byte[] readEntry(InputStream in, long size) throws IOException {
        if (size < 0 || size > (8L << 20)) {
            throw new IOException("unreasonable tar entry size: " + size);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
        copy(in, out, size);
        skipPadding(in, size);
        return out.toByteArray();
    }

    private static void skipEntry(InputStream in, long size) throws IOException {
        skipExactly(in, size);
        skipPadding(in, size);
    }

    private static void writeFile(InputStream in, File out, long size) throws IOException {
        File parent = out.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        unlinkPath(out);
        try (OutputStream os = new FileOutputStream(out)) {
            copy(in, os, size);
        }
        skipPadding(in, size);
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("truncated tar entry");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExactly(InputStream in, long size) throws IOException {
        if (size <= 0) {
            return;
        }
        long remaining = size;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("truncated tar entry");
            }
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        skipExactly(in, pad);
    }
}
