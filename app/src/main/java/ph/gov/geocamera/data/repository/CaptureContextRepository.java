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
 * Local-only capture classification state.
 *
 * Infrastructure keeps the existing Project/Site workflow. Project Activity
 * stores a local activity_project_id and is intentionally excluded from the
 * current API sync until server support is added later.
 */
public class CaptureContextRepository {

    private final GeoDbHelper dbHelper;

    public CaptureContextRepository(Context context) {
        dbHelper = new GeoDbHelper(context.getApplicationContext());
    }

    public void setCurrent(String documentationType, String activityProjectId) {
        String type = normalizeType(documentationType);
        String activityId = normalizeActivityProjectId(type, activityProjectId);

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ensureRow(db);

        ContentValues cv = new ContentValues();
        cv.put("monitoring_type", type);
        cv.put("activity_project_id", activityId);
        cv.put("shot_type", CameraPrefs.SHOT_GENERAL); // legacy column; no UI choice anymore
        cv.put("updated_at", now());
        db.update(GeoDbHelper.TABLE_CAPTURE_CONTEXT, cv, "context_id=1", null);
    }

    /** Snapshot the local metadata intended for the next shutter press. */
    public void snapshotForCapture(String documentationType, String activityProjectId) {
        String type = normalizeType(documentationType);
        String activityId = normalizeActivityProjectId(type, activityProjectId);

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ensureRow(db);

        ContentValues cv = new ContentValues();
        cv.put("pending_monitoring_type", type);
        cv.put("pending_activity_project_id", activityId);
        cv.put("pending_shot_type", CameraPrefs.SHOT_GENERAL); // compatibility only
        cv.put("updated_at", now());
        db.update(GeoDbHelper.TABLE_CAPTURE_CONTEXT, cv, "context_id=1", null);
    }

    private void ensureRow(SQLiteDatabase db) {
        db.execSQL(
                "INSERT OR IGNORE INTO " + GeoDbHelper.TABLE_CAPTURE_CONTEXT +
                        "(context_id, monitoring_type, activity_project_id, shot_type, " +
                        "pending_monitoring_type, pending_activity_project_id, pending_shot_type, updated_at) " +
                        "VALUES (1, 'UNSPECIFIED', NULL, 'GENERAL', 'UNSPECIFIED', NULL, 'GENERAL', datetime('now'))"
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

    private String normalizeActivityProjectId(String type, String value) {
        if (!CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type)) return null;
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
