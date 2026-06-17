package ph.gov.geocamera.data.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ph.gov.geocamera.data.local.db.GeoDbHelper;

public class UserRepository {

    private final GeoDbHelper dbHelper;

    public UserRepository(Context context) {
        dbHelper = new GeoDbHelper(context);
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