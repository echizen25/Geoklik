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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PhotoExportManager {

    private PhotoExportManager() {}

    // Process-memory cache. Gallery opening can use this immediately while a background
    // refresh checks MediaStore / public Pictures for the authoritative saved state.
    private static final ConcurrentHashMap<String, Boolean> SAVED_CACHE = new ConcurrentHashMap<>();

    public static final class SaveResult {
        public final Uri uri;
        public final boolean alreadySaved;

        SaveResult(Uri uri, boolean alreadySaved) {
            this.uri = uri;
            this.alreadySaved = alreadySaved;
        }
    }

    private static String cacheKey(File file) {
        if (file == null) return "";
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    public static Boolean peekSavedState(File sourceFile) {
        if (sourceFile == null) return null;
        return SAVED_CACHE.get(cacheKey(sourceFile));
    }

    public static void markSavedInCache(File sourceFile, boolean saved) {
        if (sourceFile == null) return;
        SAVED_CACHE.put(cacheKey(sourceFile), saved);
    }

    /**
     * Batch-refresh saved state. This method performs storage/MediaStore I/O and should
     * be called from a background thread. It returns a map keyed by source absolute path.
     */
    public static Map<String, Boolean> refreshSavedStates(Context context, List<File> sourceFiles) {
        if (context == null || sourceFiles == null || sourceFiles.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<String> targetNames = new HashSet<>();
        for (File file : sourceFiles) {
            if (file != null && file.getName() != null && !file.getName().trim().isEmpty()) {
                targetNames.add(file.getName());
            }
        }

        Set<String> savedNames = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? querySavedNamesMediaStore(context, targetNames)
                : scanLegacySavedNames(targetNames);

        Map<String, Boolean> result = new HashMap<>();
        for (File file : sourceFiles) {
            if (file == null) continue;
            boolean saved = savedNames.contains(file.getName());
            String key = cacheKey(file);
            SAVED_CACHE.put(key, saved);
            result.put(key, saved);
        }
        return result;
    }

    private static Set<String> querySavedNamesMediaStore(Context context, Set<String> targetNames) {
        if (targetNames == null || targetNames.isEmpty()) return Collections.emptySet();

        Set<String> found = new HashSet<>();
        ContentResolver resolver = context.getContentResolver();
        String[] projection = new String[]{
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
        };

        // Only inspect GeoKlik/legacy GeoCamera exports instead of scanning the entire photo library.
        String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? OR "
                + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = new String[]{
                Environment.DIRECTORY_PICTURES + "/GeoKlik/%",
                Environment.DIRECTORY_PICTURES + "/GeoCamera/%"
        };

        try (Cursor c = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null)) {
            if (c == null) return found;
            int nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            while (c.moveToNext()) {
                String name = nameCol >= 0 ? c.getString(nameCol) : null;
                if (name != null && targetNames.contains(name)) {
                    found.add(name);
                    if (found.size() == targetNames.size()) break;
                }
            }
        } catch (Exception ignored) {}

        return found;
    }

    private static Set<String> scanLegacySavedNames(Set<String> targetNames) {
        if (targetNames == null || targetNames.isEmpty()) return Collections.emptySet();
        Set<String> found = new HashSet<>();
        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        collectMatchingNames(new File(pictures, "GeoKlik"), targetNames, found);
        if (found.size() < targetNames.size()) {
            collectMatchingNames(new File(pictures, "GeoCamera"), targetNames, found);
        }
        return found;
    }

    private static void collectMatchingNames(File root, Set<String> targets, Set<String> found) {
        if (root == null || !root.exists() || found.size() == targets.size()) return;
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                collectMatchingNames(file, targets, found);
            } else if (targets.contains(file.getName())) {
                found.add(file.getName());
            }
            if (found.size() == targets.size()) return;
        }
    }

    public static Uri findExistingInGallery(Context context, File sourceFile) {
        if (context == null || sourceFile == null) return null;
        String displayName = sourceFile.getName();
        if (displayName == null || displayName.trim().isEmpty()) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            String[] projection = new String[]{MediaStore.Images.Media._ID};
            String selection = MediaStore.Images.Media.DISPLAY_NAME + " = ? AND ("
                    + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? OR "
                    + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?)";
            String[] args = new String[]{
                    displayName,
                    Environment.DIRECTORY_PICTURES + "/GeoKlik/%",
                    Environment.DIRECTORY_PICTURES + "/GeoCamera/%"
            };

            try (Cursor c = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    null)) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                    markSavedInCache(sourceFile, true);
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                }
            } catch (Exception ignored) {}
            markSavedInCache(sourceFile, false);
            return null;
        }

        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File found = findByName(new File(pictures, "GeoKlik"), displayName);
        if (found == null) found = findByName(new File(pictures, "GeoCamera"), displayName);
        markSavedInCache(sourceFile, found != null);
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
            markSavedInCache(sourceFile, true);
            return new SaveResult(uri, false);
        }

        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File destDir = new File(pictures, "GeoKlik" + (safeSubPath.isEmpty() ? "" : "/" + safeSubPath));
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IllegalStateException("Unable to create GeoKlik gallery folder.");
        }

        File dest = new File(destDir, sourceFile.getName());
        if (dest.exists()) {
            markSavedInCache(sourceFile, true);
            return new SaveResult(Uri.fromFile(dest), true);
        }

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

        markSavedInCache(sourceFile, true);
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
            uris.add(FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file
            ));
        }

        if (uris.isEmpty()) throw new IllegalArgumentException("Selected photos are missing.");

        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.setType("image/*");
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.setType("image/*");
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
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
