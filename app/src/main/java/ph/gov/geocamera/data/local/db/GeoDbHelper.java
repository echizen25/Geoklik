package ph.gov.geocamera.data.local.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class GeoDbHelper extends SQLiteOpenHelper {

    public static final String TABLE_USERS = "tbl_users";
    public static final String TABLE_GROUPS = "tbl_groups";
    public static final String TABLE_SITE = "tbl_site";
    public static final String TABLE_IMAGEMETA = "tbl_imagemeta";
    public static final String TABLE_PROJECTS = "tbl_projects";
    public static final String TABLE_CAPTURE_CONTEXT = "tbl_capture_context";

    public static final String DB_NAME = "geocamera.db";

    // v118 enables Project Activity captures to enter the normal pending sync
    // queue immediately now that the dedicated server upload contract is live.
    // Existing capture/upload tables and INFRA behavior are preserved.
    public static final int DB_VERSION = 118;

    public GeoDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createAll(db);
    }

    private void createAll(SQLiteDatabase db) {

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS tbl_users (" +
                        "userid TEXT PRIMARY KEY," +
                        "fname TEXT," +
                        "mname TEXT," +
                        "lname TEXT," +
                        "gender TEXT," +
                        "bdate TEXT," +
                        "designation TEXT," +
                        "project TEXT," +
                        "imei TEXT," +
                        "android_id TEXT," +
                        "uuid TEXT," +
                        "timestamp TEXT" +
                        ");"
        );

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS tbl_groups (" +
                        "groupid TEXT PRIMARY KEY," +
                        "siteid TEXT," +
                        "foldername TEXT," +
                        "motherfolder TEXT," +
                        "sessiondate TEXT," +
                        "description TEXT," +
                        "timestamp TEXT," +
                        "created_at TEXT," +
                        "updated_at TEXT," +
                        "UNIQUE(motherfolder, siteid, sessiondate)" +
                        ");"
        );

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS tbl_projects (" +
                        "projectid TEXT PRIMARY KEY," +
                        "code TEXT," +
                        "coda TEXT," +
                        "beneficiary TEXT," +
                        "location TEXT," +
                        "cost REAL," +
                        "project_type TEXT," +
                        "division_id TEXT," +
                        "division_code TEXT," +
                        "division_name TEXT," +
                        "project_implementors TEXT," +
                        "project_description TEXT," +
                        "date_from TEXT," +
                        "date_to TEXT," +
                        "timestamp TEXT" +
                        ");"
        );

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS tbl_site (" +
                        "siteid TEXT PRIMARY KEY," +
                        "projectid TEXT," +
                        "code TEXT," +
                        "imei TEXT," +
                        "name TEXT," +
                        "imageq INTEGER," +
                        "timestamp TEXT," +
                        "inprogress INTEGER DEFAULT 0," +
                        "FOREIGN KEY(projectid) REFERENCES tbl_projects(projectid)" +
                        ");"
        );

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS tbl_imagemeta (" +
                        "imagemetaid INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "uuid TEXT UNIQUE," +
                        "groupid TEXT," +
                        "siteid TEXT," +
                        "userid TEXT," +
                        "latitude REAL," +
                        "longitude REAL," +
                        "accuracy REAL," +
                        "location TEXT," +
                        "description TEXT," +
                        "erroratloc TEXT," +
                        "project TEXT," +
                        "filename TEXT," +
                        "timestamp TEXT," +
                        "status INTEGER DEFAULT 0," +
                        "sync_attempts INTEGER DEFAULT 0," +
                        "last_sync_error TEXT," +
                        "server_path TEXT," +
                        "last_sync_at TEXT," +
                        "monitoring_type TEXT," +
                        "activity_project_id TEXT," +
                        "shot_type TEXT," +
                        "FOREIGN KEY(userid) REFERENCES tbl_users(userid)," +
                        "FOREIGN KEY(groupid) REFERENCES tbl_groups(groupid)" +
                        ");"
        );

        createCaptureContextSupport(db);
        createIndexes(db);
    }

    private void createCaptureContextSupport(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS tbl_capture_context (" +
                        "context_id INTEGER PRIMARY KEY," +
                        "monitoring_type TEXT," +
                        "activity_project_id TEXT," +
                        "shot_type TEXT," +
                        "pending_monitoring_type TEXT," +
                        "pending_activity_project_id TEXT," +
                        "pending_shot_type TEXT," +
                        "updated_at TEXT" +
                        ");"
        );

        ensureColumnExists(db, TABLE_CAPTURE_CONTEXT, "activity_project_id", "TEXT");
        ensureColumnExists(db, TABLE_CAPTURE_CONTEXT, "pending_activity_project_id", "TEXT");

        db.execSQL(
                "INSERT OR IGNORE INTO tbl_capture_context(" +
                        "context_id, monitoring_type, activity_project_id, shot_type, " +
                        "pending_monitoring_type, pending_activity_project_id, pending_shot_type, updated_at" +
                        ") VALUES (1, 'UNSPECIFIED', NULL, 'GENERAL', 'UNSPECIFIED', NULL, 'GENERAL', datetime('now'));"
        );

        // Project Activity now uses the same local pending/sync state machine as INFRA.
        // The trigger only stamps capture classification metadata; it no longer forces
        // status=4 (LOCAL_ONLY). ImageMetaRepository inserts new rows as status=0.
        db.execSQL("DROP TRIGGER IF EXISTS trg_imagemeta_capture_context;");
        db.execSQL(
                "CREATE TRIGGER trg_imagemeta_capture_context " +
                        "AFTER INSERT ON tbl_imagemeta " +
                        "BEGIN " +
                        "UPDATE tbl_imagemeta SET " +
                        "monitoring_type = COALESCE((SELECT pending_monitoring_type FROM tbl_capture_context WHERE context_id=1), 'UNSPECIFIED'), " +
                        "activity_project_id = CASE " +
                        "  WHEN COALESCE((SELECT pending_monitoring_type FROM tbl_capture_context WHERE context_id=1), '') = 'PROJECT_ACTIVITY' " +
                        "  THEN (SELECT pending_activity_project_id FROM tbl_capture_context WHERE context_id=1) " +
                        "  ELSE NULL END, " +
                        "shot_type = 'GENERAL' " +
                        "WHERE imagemetaid = NEW.imagemetaid; " +
                        "END;"
        );
    }

    private void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_imagemeta_groupid ON tbl_imagemeta(groupid);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_imagemeta_siteid ON tbl_imagemeta(siteid);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_imagemeta_status ON tbl_imagemeta(status);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_imagemeta_site_time ON tbl_imagemeta(siteid, timestamp);");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_site_date ON tbl_groups(siteid, sessiondate);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_desc ON tbl_groups(description);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_imagemeta_desc ON tbl_imagemeta(description);");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_code ON tbl_projects(code);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_coda ON tbl_projects(coda);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_beneficiary ON tbl_projects(beneficiary);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_location ON tbl_projects(location);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_time ON tbl_projects(timestamp);");

        // These columns were introduced in v117. Guard their indexes because
        // createIndexes() is also called inside very old DB migration paths.
        if (getColumnType(db, "tbl_projects", "project_type") != null) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_type ON tbl_projects(project_type);");
        }
        if (getColumnType(db, "tbl_projects", "division_code") != null) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_division_code ON tbl_projects(division_code);");
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

        if (!tableExists(db, "tbl_groups") || !tableExists(db, "tbl_imagemeta")) {
            createAll(db);
            return;
        }

        String groupIdType = getColumnType(db, "tbl_groups", "groupid");
        boolean groupIdIsText = groupIdType != null && groupIdType.toUpperCase().contains("TEXT");
        if (!groupIdIsText) {
            migrateGroupsToTextIds(db);
        }

        ensureColumnExists(db, "tbl_groups", "timestamp", "TEXT");
        ensureColumnExists(db, "tbl_groups", "created_at", "TEXT");
        ensureColumnExists(db, "tbl_groups", "updated_at", "TEXT");

        try {
            db.execSQL("UPDATE tbl_groups SET created_at = COALESCE(NULLIF(created_at,''), NULLIF(timestamp,''))");
            db.execSQL("UPDATE tbl_groups SET updated_at = COALESCE(NULLIF(updated_at,''), NULLIF(timestamp,''))");
        } catch (Exception ignored) {
        }

        ensureColumnExists(db, "tbl_imagemeta", "location", "TEXT");
        ensureColumnExists(db, "tbl_imagemeta", "sync_attempts", "INTEGER DEFAULT 0");
        ensureColumnExists(db, "tbl_imagemeta", "last_sync_error", "TEXT");
        ensureColumnExists(db, "tbl_imagemeta", "server_path", "TEXT");
        ensureColumnExists(db, "tbl_imagemeta", "last_sync_at", "TEXT");
        ensureColumnExists(db, "tbl_imagemeta", "description", "TEXT");
        ensureColumnExists(db, "tbl_imagemeta", "monitoring_type", "TEXT");
        ensureColumnExists(db, "tbl_imagemeta", "activity_project_id", "TEXT");
        ensureColumnExists(db, "tbl_imagemeta", "shot_type", "TEXT");

        // v118: keep the activity identifier and release old LOCAL_ONLY rows into
        // the normal pending queue. INFRA rows and already-synced rows are untouched.
        try {
            db.execSQL(
                    "UPDATE tbl_imagemeta SET " +
                            "activity_project_id = COALESCE(NULLIF(activity_project_id,''), NULLIF(siteid,'')), " +
                            "status = CASE WHEN status = 4 THEN 0 ELSE status END, " +
                            "sync_attempts = CASE WHEN status = 4 THEN 0 ELSE sync_attempts END, " +
                            "last_sync_error = CASE WHEN status = 4 THEN NULL ELSE last_sync_error END " +
                            "WHERE monitoring_type = 'PROJECT_ACTIVITY'"
            );
        } catch (Exception ignored) {
        }

        ensureColumnExists(db, "tbl_projects", "code", "TEXT");
        ensureColumnExists(db, "tbl_projects", "coda", "TEXT");
        ensureColumnExists(db, "tbl_projects", "beneficiary", "TEXT");
        ensureColumnExists(db, "tbl_projects", "location", "TEXT");
        ensureColumnExists(db, "tbl_projects", "cost", "REAL");
        ensureColumnExists(db, "tbl_projects", "project_type", "TEXT");
        ensureColumnExists(db, "tbl_projects", "division_id", "TEXT");
        ensureColumnExists(db, "tbl_projects", "division_code", "TEXT");
        ensureColumnExists(db, "tbl_projects", "division_name", "TEXT");
        ensureColumnExists(db, "tbl_projects", "project_implementors", "TEXT");
        ensureColumnExists(db, "tbl_projects", "project_description", "TEXT");
        ensureColumnExists(db, "tbl_projects", "date_from", "TEXT");
        ensureColumnExists(db, "tbl_projects", "date_to", "TEXT");
        ensureColumnExists(db, "tbl_projects", "timestamp", "TEXT");

        // Rows saved by older Android builds did not have a type. They all came
        // from tbl_project, so INFRA is the safe migration value.
        try {
            db.execSQL(
                    "UPDATE tbl_projects SET project_type='INFRA' " +
                            "WHERE project_type IS NULL OR trim(project_type)=''"
            );
        } catch (Exception ignored) {
        }

        ensureColumnExists(db, "tbl_site", "projectid", "TEXT");
        ensureColumnExists(db, "tbl_site", "code", "TEXT");
        ensureColumnExists(db, "tbl_site", "imei", "TEXT");
        ensureColumnExists(db, "tbl_site", "name", "TEXT");
        ensureColumnExists(db, "tbl_site", "imageq", "INTEGER");
        ensureColumnExists(db, "tbl_site", "timestamp", "TEXT");
        ensureColumnExists(db, "tbl_site", "inprogress", "INTEGER DEFAULT 0");

        createCaptureContextSupport(db);
        createIndexes(db);
    }

    private void migrateGroupsToTextIds(SQLiteDatabase db) {

        db.beginTransaction();
        try {
            db.execSQL("DROP TABLE IF EXISTS tmp_group_map;");
            db.execSQL(
                    "CREATE TABLE tmp_group_map (" +
                            "old_groupid INTEGER PRIMARY KEY," +
                            "new_groupid TEXT UNIQUE" +
                            ");"
            );

            db.execSQL(
                    "INSERT INTO tmp_group_map(old_groupid, new_groupid) " +
                            "SELECT groupid, lower(hex(randomblob(16))) FROM tbl_groups;"
            );

            db.execSQL("DROP TABLE IF EXISTS tbl_groups_new;");
            db.execSQL(
                    "CREATE TABLE tbl_groups_new (" +
                            "groupid TEXT PRIMARY KEY," +
                            "siteid TEXT," +
                            "foldername TEXT," +
                            "motherfolder TEXT," +
                            "sessiondate TEXT," +
                            "description TEXT," +
                            "timestamp TEXT," +
                            "created_at TEXT," +
                            "updated_at TEXT," +
                            "UNIQUE(motherfolder, siteid, sessiondate)" +
                            ");"
            );

            db.execSQL(
                    "INSERT INTO tbl_groups_new(groupid, siteid, foldername, motherfolder, sessiondate, description, timestamp, created_at, updated_at) " +
                            "SELECT m.new_groupid, g.siteid, g.foldername, g.motherfolder, g.sessiondate, g.description, " +
                            "g.timestamp, g.timestamp, g.timestamp " +
                            "FROM tbl_groups g " +
                            "JOIN tmp_group_map m ON m.old_groupid = g.groupid;"
            );

            db.execSQL("DROP TABLE IF EXISTS tbl_imagemeta_new;");
            db.execSQL(
                    "CREATE TABLE tbl_imagemeta_new (" +
                            "imagemetaid INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "uuid TEXT UNIQUE," +
                            "groupid TEXT," +
                            "siteid TEXT," +
                            "userid TEXT," +
                            "latitude REAL," +
                            "longitude REAL," +
                            "accuracy REAL," +
                            "location TEXT," +
                            "description TEXT," +
                            "erroratloc TEXT," +
                            "project TEXT," +
                            "filename TEXT," +
                            "timestamp TEXT," +
                            "status INTEGER DEFAULT 0," +
                            "sync_attempts INTEGER DEFAULT 0," +
                            "last_sync_error TEXT," +
                            "server_path TEXT," +
                            "last_sync_at TEXT," +
                            "FOREIGN KEY(userid) REFERENCES tbl_users(userid)," +
                            "FOREIGN KEY(groupid) REFERENCES tbl_groups(groupid)" +
                            ");"
            );

            db.execSQL(
                    "INSERT INTO tbl_imagemeta_new(" +
                            "imagemetaid, uuid, groupid, siteid, userid, latitude, longitude, accuracy, " +
                            "location, description, erroratloc, project, filename, timestamp, status, " +
                            "sync_attempts, last_sync_error, server_path, last_sync_at" +
                            ") " +
                            "SELECT " +
                            "im.imagemetaid, im.uuid, m.new_groupid, im.siteid, im.userid, im.latitude, im.longitude, im.accuracy, " +
                            "im.location, im.description, im.erroratloc, im.project, im.filename, im.timestamp, im.status, " +
                            "COALESCE(im.sync_attempts,0), im.last_sync_error, im.server_path, im.last_sync_at " +
                            "FROM tbl_imagemeta im " +
                            "LEFT JOIN tmp_group_map m ON m.old_groupid = im.groupid;"
            );

            db.execSQL("DROP TABLE tbl_imagemeta;");
            db.execSQL("ALTER TABLE tbl_imagemeta_new RENAME TO tbl_imagemeta;");

            db.execSQL("DROP TABLE tbl_groups;");
            db.execSQL("ALTER TABLE tbl_groups_new RENAME TO tbl_groups;");

            db.execSQL("DROP TABLE IF EXISTS tmp_group_map;");

            createIndexes(db);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private boolean tableExists(SQLiteDatabase db, String tableName) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{tableName}
            );
            return c.moveToFirst();
        } finally {
            if (c != null) c.close();
        }
    }

    private String getColumnType(SQLiteDatabase db, String table, String column) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndexOrThrow("name"));
                if (column.equalsIgnoreCase(name)) {
                    return c.getString(c.getColumnIndexOrThrow("type"));
                }
            }
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private void ensureColumnExists(SQLiteDatabase db, String table, String column, String type) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            boolean found = false;
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndexOrThrow("name"));
                if (column.equalsIgnoreCase(name)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            }
        } finally {
            if (c != null) c.close();
        }
    }
}
