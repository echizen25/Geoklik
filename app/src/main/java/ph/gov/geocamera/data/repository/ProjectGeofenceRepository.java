package ph.gov.geocamera.data.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.data.local.db.GeoDbHelper;
import ph.gov.geocamera.data.remote.ApiProjectItem;

/**
 * Local cache for optional capture-area geofences returned by /capture-targets.
 *
 * Kept in a separate table so the existing tbl_projects schema and its legacy
 * migrations stay untouched. The table lives in the same geocamera.db file and
 * is keyed by the same projectid used by both INFRA and PROJECT_ACTIVITY.
 */
public class ProjectGeofenceRepository {

    public static final String TABLE = "tbl_project_geofence";

    private final GeoDbHelper dbHelper;

    public ProjectGeofenceRepository(Context context) {
        dbHelper = new GeoDbHelper(context.getApplicationContext());
        ensureTable();
    }

    private void ensureTable() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try {
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                            "projectid TEXT PRIMARY KEY," +
                            "latitude REAL NOT NULL," +
                            "longitude REAL NOT NULL," +
                            "radius_m REAL NOT NULL," +
                            "updated_at TEXT" +
                            ")"
            );
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_project_geofence_projectid ON " + TABLE + "(projectid)");
        } finally {
            db.close();
        }
    }

    /**
     * Mirrors geofence state only when the source response actually supports
     * the geofence contract. A legacy /projects fallback intentionally leaves
     * the existing cache untouched because that endpoint cannot express
     * "configured" versus "disabled" geofences.
     */
    public void saveFromApi(List<ApiProjectItem> items) {
        if (items == null) return;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            String now = now();

            for (ApiProjectItem item : items) {
                if (item == null || !item.geofenceMetadataAvailable) continue;

                String projectId = clean(item.projectId);
                if (projectId.isEmpty()) continue;

                if (!isValid(item.geofenceLatitude, item.geofenceLongitude, item.geofenceRadiusMeters)) {
                    // Null/incomplete values from /capture-targets explicitly mean
                    // "geofence not configured" for this project.
                    db.delete(TABLE, "projectid=?", new String[]{projectId});
                    continue;
                }

                ContentValues cv = new ContentValues();
                cv.put("projectid", projectId);
                cv.put("latitude", item.geofenceLatitude);
                cv.put("longitude", item.geofenceLongitude);
                cv.put("radius_m", item.geofenceRadiusMeters);
                cv.put("updated_at", now);

                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    public Config getByProjectId(String projectId) {
        String id = clean(projectId);
        if (id.isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT latitude, longitude, radius_m FROM " + TABLE + " WHERE projectid=? LIMIT 1",
                    new String[]{id}
            );

            if (!c.moveToFirst()) return null;

            double lat = c.getDouble(0);
            double lng = c.getDouble(1);
            double radius = c.getDouble(2);

            if (!isValid(lat, lng, radius)) return null;
            return new Config(id, lat, lng, radius);
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public boolean hasConfiguredGeofence(String projectId) {
        return getByProjectId(projectId) != null;
    }

    private static boolean isValid(Double lat, Double lng, Double radius) {
        if (lat == null || lng == null || radius == null) return false;
        return isValid(lat.doubleValue(), lng.doubleValue(), radius.doubleValue());
    }

    private static boolean isValid(double lat, double lng, double radius) {
        return Double.isFinite(lat)
                && Double.isFinite(lng)
                && Double.isFinite(radius)
                && lat >= -90d && lat <= 90d
                && lng >= -180d && lng <= 180d
                && radius > 0d && radius <= 100000d;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    public static final class Config {
        public final String projectId;
        public final double latitude;
        public final double longitude;
        public final double radiusMeters;

        Config(String projectId, double latitude, double longitude, double radiusMeters) {
            this.projectId = projectId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.radiusMeters = radiusMeters;
        }
    }
}
