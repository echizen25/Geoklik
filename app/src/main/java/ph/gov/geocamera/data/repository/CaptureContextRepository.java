package ph.gov.geocamera.data.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.local.db.GeoDbHelper;

/**
 * Stores the current local-only documentation metadata used by the camera.
 *
 * This repository deliberately does not touch the upload/API layer. A local
 * SQLite trigger copies the pending values onto a newly inserted image row.
 */
public class CaptureContextRepository {

    private final GeoDbHelper dbHelper;

    public CaptureContextRepository(Context context) {
        dbHelper = new GeoDbHelper(context.getApplicationContext());
    }

    public void setCurrent(String documentationType, String shotType) {
        String type = normalizeType(documentationType);
        String shot = normalizeShot(shotType);

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ensureRow(db);

        ContentValues cv = new ContentValues();
        cv.put("monitoring_type", type);
        cv.put("shot_type", shot);
        cv.put("pending_monitoring_type", type);
        cv.put("pending_shot_type", shot);
        cv.put("updated_at", now());
        db.update(GeoDbHelper.TABLE_CAPTURE_CONTEXT, cv, "context_id=1", null);
    }

    /**
     * Snapshot the values that should be attached to the next captured photo.
     * This is called on shutter touch so changing the UI afterwards will not
     * change the metadata intended for that capture.
     */
    public void snapshotForCapture(String documentationType, String shotType) {
        String type = normalizeType(documentationType);
        String shot = normalizeShot(shotType);

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ensureRow(db);

        ContentValues cv = new ContentValues();
        cv.put("pending_monitoring_type", type);
        cv.put("pending_shot_type", shot);
        cv.put("updated_at", now());
        db.update(GeoDbHelper.TABLE_CAPTURE_CONTEXT, cv, "context_id=1", null);
    }

    private void ensureRow(SQLiteDatabase db) {
        db.execSQL(
                "INSERT OR IGNORE INTO " + GeoDbHelper.TABLE_CAPTURE_CONTEXT +
                        "(context_id, monitoring_type, shot_type, pending_monitoring_type, pending_shot_type, updated_at) " +
                        "VALUES (1, 'UNSPECIFIED', 'GENERAL', 'UNSPECIFIED', 'GENERAL', datetime('now'))"
        );
    }

    private String normalizeType(String value) {
        if (value != null && CameraPrefs.DOC_INFRA.equalsIgnoreCase(value.trim())) {
            return CameraPrefs.DOC_INFRA;
        }
        if (value != null && CameraPrefs.DOC_PROJECT_ACTIVITY.equalsIgnoreCase(value.trim())) {
            return CameraPrefs.DOC_PROJECT_ACTIVITY;
        }
        return "UNSPECIFIED";
    }

    private String normalizeShot(String value) {
        if (value == null || value.trim().isEmpty()) return CameraPrefs.SHOT_GENERAL;
        return value.trim().toUpperCase(Locale.US);
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
