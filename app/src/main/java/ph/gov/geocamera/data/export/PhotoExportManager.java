package ph.gov.geocamera.data.export;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class PhotoExportManager {

    private PhotoExportManager() {}

    public static final class SaveResult {
        public final Uri uri;
        public final boolean alreadySaved;

        SaveResult(Uri uri, boolean alreadySaved) {
            this.uri = uri;
            this.alreadySaved = alreadySaved;
        }
    }

    public static Uri findExistingInGallery(Context context, File sourceFile) {
        if (context == null || sourceFile == null) return null;
        String displayName = sourceFile.getName();
        if (displayName == null || displayName.trim().isEmpty()) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            String[] projection = new String[]{MediaStore.Images.Media._ID};
            String selection = MediaStore.Images.Media.DISPLAY_NAME + " = ?";
            String[] args = new String[]{displayName};

            try (Cursor c = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    null)) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                }
            } catch (Exception ignored) {}
            return null;
        }

        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File geoKlik = new File(pictures, "GeoKlik");
        File geoCamera = new File(pictures, "GeoCamera");
        File found = findByName(geoKlik, displayName);
        if (found == null) found = findByName(geoCamera, displayName);
        return found == null ? null : Uri.fromFile(found);
    }

    public static SaveResult saveToDevice(Context context, File sourceFile, String subPath) throws Exception {
        if (context == null) throw new IllegalArgumentException("Context is required.");
        if (sourceFile == null || !sourceFile.exists()) {
            throw new IllegalArgumentException("Source photo not found.");
        }

        Uri existing = findExistingInGallery(context, sourceFile);
        if (existing != null) return new SaveResult(existing, true);

        String safeSubPath = normalizeSubPath(subPath);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String relativePath = Environment.DIRECTORY_PICTURES + "/GeoKlik/";
            if (!safeSubPath.isEmpty()) relativePath += safeSubPath + "/";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, sourceFile.getName());
            values.put(MediaStore.Images.Media.MIME_TYPE, mimeType(sourceFile));
            values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Unable to create gallery item.");

            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Unable to open gallery output stream.");
                copy(in, out);
            } catch (Exception e) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
                throw e;
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return new SaveResult(uri, false);
        }

        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File destDir = new File(pictures, "GeoKlik" + (safeSubPath.isEmpty() ? "" : "/" + safeSubPath));
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IllegalStateException("Unable to create GeoKlik gallery folder.");
        }

        File dest = new File(destDir, sourceFile.getName());
        if (dest.exists()) return new SaveResult(Uri.fromFile(dest), true);

        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }

        android.media.MediaScannerConnection.scanFile(
                context,
                new String[]{dest.getAbsolutePath()},
                new String[]{mimeType(sourceFile)},
                null
        );

        return new SaveResult(Uri.fromFile(dest), false);
    }

    public static void sharePhoto(Context context, File sourceFile, String chooserTitle) {
        if (context == null || sourceFile == null || !sourceFile.exists()) {
            throw new IllegalArgumentException("Photo not found.");
        }

        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                sourceFile
        );

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mimeType(sourceFile));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        context.startActivity(Intent.createChooser(
                send,
                chooserTitle == null || chooserTitle.trim().isEmpty() ? "Share GeoKlik photo" : chooserTitle
        ));
    }

    public static void sharePhotos(Context context, List<File> sourceFiles, String chooserTitle) {
        if (context == null) throw new IllegalArgumentException("Context is required.");
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No photos selected.");
        }

        ArrayList<Uri> uris = new ArrayList<>();
        for (File file : sourceFiles) {
            if (file == null || !file.exists()) continue;
            Uri uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file
            );
            uris.add(uri);
        }

        if (uris.isEmpty()) throw new IllegalArgumentException("Selected photos are missing.");

        if (uris.size() == 1) {
            sharePhoto(context, sourceFiles.get(0), chooserTitle);
            return;
        }

        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
        send.setType("image/*");
        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        context.startActivity(Intent.createChooser(
                send,
                chooserTitle == null || chooserTitle.trim().isEmpty()
                        ? "Share selected GeoKlik photos"
                        : chooserTitle
        ));
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        out.flush();
    }

    private static String normalizeSubPath(String value) {
        if (value == null) return "";
        String s = value.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        s = s.replaceAll("[:*?\"<>|]", "_");
        return s;
    }

    private static String mimeType(File file) {
        String name = file == null ? "" : file.getName().toLowerCase(java.util.Locale.US);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static File findByName(File root, String name) {
        if (root == null || !root.exists()) return null;
        File[] files = root.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && name.equalsIgnoreCase(f.getName())) return f;
            if (f.isDirectory()) {
                File nested = findByName(f, name);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
