package com.agy.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal read-only ContentProvider that exposes the staged OTA update APK
 * inside cacheDir/ota_updates/update.apk to Android's system PackageInstaller
 * via content://&lt;package&gt;.updateprovider/update.apk without AndroidX.
 */
public final class UpdateApkProvider extends ContentProvider {

    private static final String MIME_APK = "application/vnd.android.package-archive";

    public static Uri buildUpdateUri(Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".updateprovider/update.apk");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_APK;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        File apk = AppUpdateManager.getStagedApkFile(ctx);
        String[] cols = projection != null && projection.length > 0
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            String col = cols[i];
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row[i] = apk.getName();
            } else if (OpenableColumns.SIZE.equals(col)) {
                row[i] = apk.isFile() ? apk.length() : 0L;
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) {
            throw new SecurityException("UpdateApkProvider is read-only");
        }
        Context ctx = getContext();
        if (ctx == null) {
            throw new FileNotFoundException("No context");
        }
        File apk = AppUpdateManager.getStagedApkFile(ctx);
        if (!apk.isFile() || apk.length() <= 0L) {
            throw new FileNotFoundException("Staged update APK not found");
        }
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
