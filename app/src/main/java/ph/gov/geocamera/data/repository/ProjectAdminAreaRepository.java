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
 * Companion cache for Infrastructure municipality/barangay authorization data.
 *
 * This intentionally uses its own table instead of changing tbl_projects again,
 * so existing installed databases and the current project cache remain compatible.
 */
public final class ProjectAdminAreaRepository {

    private static final String TABLE = "tbl_project_admin_area";
    private final GeoDbHelper dbHelper;

    public ProjectAdminAreaRepository(Context context) {
        dbHelper = new GeoDbHelper(context.getApplicationContext());
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ensureTable(db);
    }

    public void saveFromApi(List<ApiProjectItem> items) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ensureTable(db);
        db.beginTransaction();
        try {
            if (items != null) {
                for (ApiProjectItem item : items) {
                    if (item == null || !item.adminAreaMetadataAvailable) continue;

                    String projectId = clean(item.projectId);
                    if (projectId.isEmpty()) continue;

                    String type = clean(item.projectType).toUpperCase(Locale.US);
                    if (!"INFRA".equals(type)) {
                        db.delete(TABLE, "projectid=?", new String[]{projectId});
                        continue;
                    }

                    ContentValues cv = new ContentValues();
                    cv.put("projectid", projectId);
                    cv.put("project_type", "INFRA");
                    cv.put("mun_code", clean(item.municipalityCode));
                    cv.put("brgy_code", clean(item.barangayCode));
                    cv.put("metadata_available", 1);
                    cv.put("updated_at", now());

                    db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public Record getByProjectId(String projectId) {
        String id = clean(projectId);
        if (id.isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        ensureTable(db);
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT project_type, mun_code, brgy_code, metadata_available, updated_at " +
                            "FROM " + TABLE + " WHERE projectid=? LIMIT 1",
                    new String[]{id}
            );
            if (!c.moveToFirst()) return null;

            Record r = new Record();
            r.projectId = id;
            r.projectType = c.isNull(0) ? "" : clean(c.getString(0));
            r.municipalityCode = c.isNull(1) ? "" : clean(c.getString(1));
            r.barangayCode = c.isNull(2) ? "" : clean(c.getString(2));
            r.metadataAvailable = !c.isNull(3) && c.getInt(3) == 1;
            r.updatedAt = c.isNull(4) ? "" : clean(c.getString(4));
            return r;
        } finally {
            if (c != null) c.close();
        }
    }

    private static void ensureTable(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                        "projectid TEXT PRIMARY KEY," +
                        "project_type TEXT," +
                        "mun_code TEXT," +
                        "brgy_code TEXT," +
                        "metadata_available INTEGER DEFAULT 0," +
                        "updated_at TEXT" +
                        ")"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_project_admin_area_codes ON " +
                TABLE + "(mun_code, brgy_code)");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    public static final class Record {
        public String projectId = "";
        public String projectType = "";
        public String municipalityCode = "";
        public String barangayCode = "";
        public boolean metadataAvailable;
        public String updatedAt = "";

        public boolean hasCodes() {
            return metadataAvailable
                    && municipalityCode != null && !municipalityCode.trim().isEmpty()
                    && barangayCode != null && !barangayCode.trim().isEmpty();
        }
    }
}
