package ph.gov.geocamera.data.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.local.db.GeoDbHelper;

public class UserRepository {

    private final GeoDbHelper dbHelper;
    private final Context sourceContext;

    public UserRepository(Context context) {
        sourceContext = context;
        dbHelper = new GeoDbHelper(context.getApplicationContext());
    }

    // -------------------------------------
    // Insert User (for FirstLaunchActivity)
    // -------------------------------------
    public long insertUser(
            String userId,
            String fname,
            String mname,
            String lname,
            String gender,
            String bdate,
            String designation,
            String project,
            String imei,
            String androidId,
            String uuid
    ) {

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues cv = new ContentValues();
        cv.put("userid", userId);
        cv.put("fname", fname);
        cv.put("mname", mname);
        cv.put("lname", lname);
        cv.put("gender", gender);
        cv.put("bdate", bdate);
        cv.put("designation", designation);
        cv.put("project", project);
        cv.put("imei", imei);
        cv.put("android_id", androidId);
        cv.put("uuid", uuid);
        cv.put("timestamp", now());

        return db.insert("tbl_users", null, cv);
    }

    // -------------------------------------
    // Check if user exists
    // -------------------------------------
    public boolean hasUser() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM tbl_users", null);

        boolean has = false;
        if (c.moveToFirst()) has = c.getInt(0) > 0;
        c.close();
        return has;
    }

    // -------------------------------------
    // Getters
    // -------------------------------------
    public String getFirstName() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT fname FROM tbl_users ORDER BY timestamp DESC LIMIT 1", null);

        String val = null;
        if (c.moveToFirst()) val = c.getString(0);
        c.close();
        return val;
    }

    public String getProject() {
        // Only GeoCameraActivity changes its displayed/captured project label in
        // Project Activity mode. Everywhere else still receives the user's
        // original funding/program value (RCEF, CTF, PHILMECH, etc.).
        String activityLabel = getCameraActivityProjectLabel();
        if (activityLabel != null && !activityLabel.trim().isEmpty()) {
            return activityLabel.trim();
        }

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT project FROM tbl_users ORDER BY timestamp DESC LIMIT 1", null);

        String val = null;
        if (c.moveToFirst()) val = c.getString(0);
        c.close();
        return val;
    }

    private String getCameraActivityProjectLabel() {
        if (sourceContext == null
                || !"ph.gov.geocamera.presentation.geocamera.GeoCameraActivity"
                .equals(sourceContext.getClass().getName())) {
            return null;
        }

        CameraPrefs prefs = new CameraPrefs(sourceContext);
        if (!CameraPrefs.DOC_PROJECT_ACTIVITY.equals(prefs.getDocumentationType())) {
            return null;
        }

        String projectId = prefs.getActivityProjectId();
        if (projectId == null || projectId.trim().isEmpty()) return "PROJECT ACTIVITY";
        projectId = projectId.trim();

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT coda, code FROM tbl_projects " +
                            "WHERE trim(projectid)=trim(?) COLLATE NOCASE " +
                            "   OR trim(code)=trim(?) COLLATE NOCASE " +
                            "LIMIT 1",
                    new String[]{projectId, projectId}
            );

            if (c.moveToFirst()) {
                String coda = c.isNull(0) ? "" : clean(c.getString(0));
                String code = c.isNull(1) ? "" : clean(c.getString(1));
                if (!coda.isEmpty()) return safeCameraProjectLabel(coda);
                if (!code.isEmpty()) return safeCameraProjectLabel(code);
            }
        } finally {
            if (c != null) c.close();
        }

        return safeCameraProjectLabel(projectId);
    }

    /** Keep the label usable both on the watermark and as the local photo folder name. */
    private String safeCameraProjectLabel(String value) {
        String v = clean(value);
        if (v.isEmpty()) return "PROJECT ACTIVITY";
        v = v.replaceAll("[\\\\/:*?\"<>|]", "-");
        while (v.contains("  ")) v = v.replace("  ", " ");
        return v.trim();
    }

    private String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public String getUserId() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT userid FROM tbl_users ORDER BY timestamp DESC LIMIT 1", null);

        String val = null;
        if (c.moveToFirst()) val = c.getString(0);
        c.close();
        return val;
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
