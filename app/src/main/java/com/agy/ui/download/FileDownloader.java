package com.agy.ui.download;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Handles file downloads initiated from the WebView with safe escaping, path containment,
 * UI-thread safety, and Android 11+ storage support.
 */
public final class FileDownloader {

    private static final String TAG = "FileDownloader";
    public static final int REQUEST_STORAGE_DOWNLOAD = 2001;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static class DownloadTask {
        public final String url;
        public final String userAgent;
        public final String contentDisposition;
        public final String mimeType;
        public final long contentLength;
        public final String base64Data; // set if blob download

        public DownloadTask(String url, String userAgent, String contentDisposition,
                            String mimeType, long contentLength, String base64Data) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.contentLength = contentLength;
            this.base64Data = base64Data;
        }
    }

    private static volatile DownloadTask pendingTask;

    private FileDownloader() {
    }

    private static void toastOnMain(final Context context, final String message, final int duration) {
        if (context == null) return;
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context.getApplicationContext(), message, duration).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String sanitizeFileName(String raw) {
        String fallback = "download_" + System.currentTimeMillis();
        if (raw == null) return fallback;
        String base = new File(raw.trim()).getName();
        base = base.replace('/', '_').replace('\\', '_').replace("\r", "").replace("\n", "").replace("\0", "").trim();
        while (base.startsWith(".")) {
            base = base.substring(1).trim();
        }
        return base.isEmpty() ? fallback : base;
    }

    private static String redactUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int cut = (q >= 0 && h >= 0) ? Math.min(q, h) : (q >= 0 ? q : h);
        return cut >= 0 ? url.substring(0, cut) : url;
    }

    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager()
                    || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static void requestStoragePermission(final Activity activity, final DownloadTask task) {
        if (activity == null) return;
        pendingTask = task;
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= 30) {
                    // On Android 11+ scoped storage allows writing new files to public Downloads directly
                    executeTask(activity, null, task);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Toast.makeText(activity, "Storage permission is required to save downloads", Toast.LENGTH_SHORT).show();
                    activity.requestPermissions(new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    }, REQUEST_STORAGE_DOWNLOAD);
                } else {
                    executeTask(activity, null, task);
                }
            }
        });
    }

    public static void onPermissionResult(Activity activity, WebView webView, int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_STORAGE_DOWNLOAD) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            if (pendingTask != null) {
                DownloadTask task = pendingTask;
                pendingTask = null;
                executeTask(activity, webView, task);
            }
        } else {
            pendingTask = null;
            toastOnMain(activity, "Storage permission denied", Toast.LENGTH_LONG);
        }
    }

    public static void handleDownload(final Activity activity, final WebView webView, String url,
                                      String userAgent, String contentDisposition,
                                      String mimeType, long contentLength) {
        final DownloadTask task = new DownloadTask(url, userAgent, contentDisposition, mimeType, contentLength, null);
        if (!hasStoragePermission(activity)) {
            requestStoragePermission(activity, task);
        } else {
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    executeTask(activity, webView, task);
                }
            });
        }
    }

    public static void handleBlobData(final Activity activity, String base64Data, String filename, String mimeType) {
        final DownloadTask task = new DownloadTask(null, null, filename, mimeType, 0, base64Data);
        if (!hasStoragePermission(activity)) {
            requestStoragePermission(activity, task);
        } else {
            executeTask(activity, null, task);
        }
    }

    private static void executeTask(final Activity activity, final WebView webView, final DownloadTask task) {
        if (task == null || activity == null) {
            return;
        }

        // Case 1: Base64 blob data
        if (task.base64Data != null) {
            saveBase64ToDownloads(activity, task.base64Data, task.contentDisposition, task.mimeType);
            return;
        }

        if (task.url == null || task.url.trim().isEmpty()) {
            return;
        }

        final String rawUrl = task.url.trim();

        // Case 2a: data: URL -> decode natively in Java (never splice into evaluateJavascript)
        if (rawUrl.startsWith("data:")) {
            String safeName = sanitizeFileName(URLUtil.guessFileName(rawUrl, task.contentDisposition, task.mimeType));
            saveBase64ToDownloads(activity, rawUrl, safeName, task.mimeType);
            return;
        }

        // Case 2b: blob: URL -> extract data via safely JSON-quoted JS in WebView
        if (rawUrl.startsWith("blob:")) {
            if (webView != null) {
                String safeName = sanitizeFileName(URLUtil.guessFileName(rawUrl, task.contentDisposition, task.mimeType));
                extractBlobViaJs(webView, rawUrl, safeName, task.mimeType);
            } else {
                toastOnMain(activity, "Cannot download blob without WebView", Toast.LENGTH_SHORT);
            }
            return;
        }

        // Case 3: Loopback (127.0.0.1 / localhost) -> direct internal download to avoid DownloadManager TLS errors
        Uri uri = Uri.parse(rawUrl);
        String host = uri.getHost();
        if (com.agy.util.LoopbackHost.isLoopback(host)) {
            downloadDirect(activity, rawUrl, task.contentDisposition, task.mimeType);
            return;
        }

        // Case 4: Standard HTTP / HTTPS -> enqueue in system DownloadManager
        try {
            DownloadManager.Request request = new DownloadManager.Request(uri);
            if (task.mimeType != null && !task.mimeType.isEmpty()) {
                request.setMimeType(task.mimeType);
            }
            String fileName = sanitizeFileName(URLUtil.guessFileName(rawUrl, task.contentDisposition, task.mimeType));
            request.setTitle(fileName);
            request.setDescription("Downloading file");
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            if (task.userAgent != null && !task.userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", task.userAgent);
            }
            String cookies = CookieManager.getInstance().getCookie(rawUrl);
            if (cookies != null && !cookies.isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                toastOnMain(activity, "Downloading: " + fileName, Toast.LENGTH_SHORT);
                Log.i(TAG, "Download enqueued: " + fileName + " url=" + redactUrl(rawUrl));
            }
        } catch (Exception e) {
            Log.w(TAG, "DownloadManager failed, falling back to direct download: " + e.getMessage());
            downloadDirect(activity, rawUrl, task.contentDisposition, task.mimeType);
        }
    }

    private static void extractBlobViaJs(final WebView webView, String blobUrl, String filename, String mimeType) {
        final String qUrl = JSONObject.quote(blobUrl);
        final String qName = JSONObject.quote(sanitizeFileName(filename));
        final String qMime = JSONObject.quote(mimeType != null ? mimeType : "");
        final String js = "(function() {" +
                "  try {" +
                "    var u = " + qUrl + ";" +
                "    if (typeof u !== 'string' || u.indexOf('blob:') !== 0) return;" +
                "    var xhr = new XMLHttpRequest();" +
                "    xhr.open('GET', u, true);" +
                "    xhr.responseType = 'blob';" +
                "    xhr.onload = function() {" +
                "      if (xhr.status === 200 || xhr.status === 0) {" +
                "        var reader = new FileReader();" +
                "        reader.onloadend = function() {" +
                "          if (window.Android && window.Android.saveBlob) {" +
                "            window.Android.saveBlob(reader.result, " + qName + ", " + qMime + ");" +
                "          }" +
                "        };" +
                "        reader.readAsDataURL(xhr.response);" +
                "      }" +
                "    };" +
                "    xhr.send();" +
                "  } catch(e) {" +
                "    console.error('Failed to extract blob', e);" +
                "  }" +
                "})();";
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    public static void saveBase64ToDownloads(final Context context, final String dataUrlOrBase64,
                                             final String filename, final String mimeType) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String base64 = dataUrlOrBase64;
                    int comma = base64.indexOf(',');
                    if (comma >= 0) {
                        base64 = base64.substring(comma + 1);
                    }
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs();
                    }

                    String name = sanitizeFileName(filename);
                    File target = getUniqueFile(new File(downloadsDir, name));
                    String canonBase = downloadsDir.getCanonicalPath() + File.separator;
                    if (!target.getCanonicalPath().startsWith(canonBase)) {
                        throw new SecurityException("Invalid download target path");
                    }

                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        fos.write(bytes);
                        fos.flush();
                    }

                    final File savedFile = target;
                    MediaScannerConnection.scanFile(context, new String[]{savedFile.getAbsolutePath()}, null, null);
                    toastOnMain(context, "Saved to Downloads: " + savedFile.getName(), Toast.LENGTH_LONG);
                    Log.i(TAG, "Blob saved to: " + savedFile.getAbsolutePath());
                } catch (final Exception e) {
                    Log.e(TAG, "Failed to save blob", e);
                    toastOnMain(context, "Failed to save file: " + e.getMessage(), Toast.LENGTH_SHORT);
                }
            }
        }).start();
    }

    private static void downloadDirect(final Activity activity, final String fileUrl,
                                       final String contentDisposition, final String mimeType) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL u = new URL(fileUrl);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 Antigravity");
                    conn.connect();

                    if (conn.getResponseCode() >= 400) {
                        throw new RuntimeException("HTTP " + conn.getResponseCode());
                    }

                    String disp = conn.getHeaderField("Content-Disposition");
                    if (disp == null || disp.isEmpty()) {
                        disp = contentDisposition;
                    }
                    String type = conn.getContentType();
                    if (type == null || type.isEmpty()) {
                        type = mimeType;
                    }

                    String fileName = sanitizeFileName(URLUtil.guessFileName(fileUrl, disp, type));
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs();
                    }

                    File target = getUniqueFile(new File(downloadsDir, fileName));
                    String canonBase = downloadsDir.getCanonicalPath() + File.separator;
                    if (!target.getCanonicalPath().startsWith(canonBase)) {
                        throw new SecurityException("Invalid download target path");
                    }

                    try (InputStream in = conn.getInputStream();
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        out.flush();
                    }

                    final File savedFile = target;
                    MediaScannerConnection.scanFile(activity, new String[]{savedFile.getAbsolutePath()}, null, null);
                    toastOnMain(activity, "Saved to Downloads: " + savedFile.getName(), Toast.LENGTH_LONG);
                    Log.i(TAG, "Direct download saved: " + savedFile.getAbsolutePath());
                } catch (final Exception e) {
                    Log.e(TAG, "Direct download failed", e);
                    toastOnMain(activity, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT);
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }).start();
    }

    private static File getUniqueFile(File file) {
        if (!file.exists()) {
            return file;
        }
        String parent = file.getParent();
        String name = file.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int count = 1;
        while (file.exists()) {
            file = new File(parent, base + " (" + count + ")" + ext);
            count++;
        }
        return file;
    }
}
