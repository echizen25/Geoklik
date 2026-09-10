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

    public boolean hasUser() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM tbl_users", null);

        boolean has = false;
        if (c.moveToFirst()) has = c.getInt(0) > 0;
        c.close();
        return has;
    }

    public String getFirstName() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT fname FROM tbl_users ORDER BY timestamp DESC LIMIT 1", null);

        String val = null;
        if (c.moveToFirst()) val = c.getString(0);
        c.close();
        return val;
    }

    /**
     * Funding/program label used by the Camera overlay.
     *
     * The previous implementation depended on the Context class name being
     * exactly GeoCameraActivity. That was unnecessarily fragile and could make
     * Personal Capture fall through to the saved profile value such as RCEF.
     * CameraPrefs is now the authoritative capture-mode source:
     *
     * PERSONAL          -> user's Personal Overlay Label
     * PROJECT_ACTIVITY  -> PROJECT ACTIVITY
     * INFRA / no mode   -> stored profile project (RCEF, CTF, etc.)
     *
     * This does not overwrite tbl_users.project.
     */
    public String getProject() {
        if (sourceContext != null) {
            try {
                CameraPrefs prefs = new CameraPrefs(sourceContext);
                String type = prefs.getDocumentationType();

                if (CameraPrefs.DOC_PERSONAL.equals(type)) {
                    return prefs.getPersonalOverlayLabel();
                }

                if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type)) {
                    return "PROJECT ACTIVITY";
                }
            } catch (Exception ignored) {}
        }

        return getStoredProject();
    }

    /** Original profile project/funding value, unaffected by capture mode. */
    public String getStoredProject() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT project FROM tbl_users ORDER BY timestamp DESC LIMIT 1", null);

        String val = null;
        if (c.moveToFirst()) val = c.getString(0);
        c.close();
        return val;
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
