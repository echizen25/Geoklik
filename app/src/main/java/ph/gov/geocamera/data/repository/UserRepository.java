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

    public static final class UserProfile {
        public String userId;
        public String firstName;
        public String middleName;
        public String lastName;
        public String designation;
        public String project;

        public String fullName() {
            StringBuilder out = new StringBuilder();
            appendPart(out, firstName);
            appendPart(out, middleName);
            appendPart(out, lastName);
            return out.toString().trim();
        }

        private static void appendPart(StringBuilder out, String value) {
            if (value == null || value.trim().isEmpty()) return;
            if (out.length() > 0) out.append(' ');
            out.append(value.trim());
        }
    }

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

        long result = db.insert("tbl_users", null, cv);
        db.close();
        return result;
    }

    public boolean hasUser() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM tbl_users", null);

        boolean has = false;
        if (c.moveToFirst()) has = c.getInt(0) > 0;
        c.close();
        db.close();
        return has;
    }

    public UserProfile getProfile() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT userid, fname, mname, lname, designation, project " +
                            "FROM tbl_users ORDER BY timestamp DESC LIMIT 1",
                    null
            );
            if (!c.moveToFirst()) return null;

            UserProfile p = new UserProfile();
            p.userId = valueAt(c, 0);
            p.firstName = valueAt(c, 1);
            p.middleName = valueAt(c, 2);
            p.lastName = valueAt(c, 3);
            p.designation = valueAt(c, 4);
            p.project = valueAt(c, 5);
            return p;
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    /** Updates only editable profile fields. Device identity fields are untouched. */
    public boolean updateProfile(String firstName,
                                 String middleName,
                                 String lastName,
                                 String designation,
                                 String project) {
        UserProfile current = getProfile();
        if (current == null || current.userId == null || current.userId.trim().isEmpty()) {
            return false;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        try {
            ContentValues cv = new ContentValues();
            cv.put("fname", clean(firstName));
            cv.put("mname", clean(middleName));
            cv.put("lname", clean(lastName));
            cv.put("designation", clean(designation));
            cv.put("project", clean(project));
            cv.put("timestamp", now());

            return db.update(
                    GeoDbHelper.TABLE_USERS,
                    cv,
                    "userid = ?",
                    new String[]{current.userId}
            ) > 0;
        } finally {
            db.close();
        }
    }

    public String getFirstName() {
        UserProfile p = getProfile();
        return p == null ? null : p.firstName;
    }

    public String getFullName() {
        UserProfile p = getProfile();
        return p == null ? null : p.fullName();
    }

    public String getDesignation() {
        UserProfile p = getProfile();
        return p == null ? null : p.designation;
    }

    /**
     * Funding/program label used by the Camera overlay.
     * PERSONAL and PROJECT_ACTIVITY override the stored profile project only for
     * the active capture mode. The stored profile value itself is not overwritten.
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
        UserProfile p = getProfile();
        return p == null ? null : p.project;
    }

    public String getUserId() {
        UserProfile p = getProfile();
        return p == null ? null : p.userId;
    }

    private static String valueAt(Cursor c, int index) {
        if (c == null || c.isNull(index)) return "";
        String value = c.getString(index);
        return value == null ? "" : value.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
