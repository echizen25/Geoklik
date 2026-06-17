package ph.gov.geocamera.data.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import ph.gov.geocamera.data.local.db.GeoDbHelper;

public class GroupRepository {

    private final GeoDbHelper dbHelper;

    public GroupRepository(Context context) {
        dbHelper = new GeoDbHelper(context);
    }

    public String getOrCreateGroup(String motherFolder,
                                   String siteId,
                                   String sessionDate,
                                   String remarks) {

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        String mf = safe(motherFolder);
        String sid = safe(siteId);
        String sd = safe(sessionDate);

        if (mf.isEmpty()) mf = "PROJECT_0000";
        if (sid.isEmpty()) sid = "UNCAT";
        if (sd.isEmpty()) sd = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        String rem = safeRemarks(remarks);
        String folderRel = mf + "/" + sid + "/" + sd;

        String existingId = findGroupIdByDate(db, mf, sid, sd);
        if (existingId != null) {

            ContentValues up = new ContentValues();
            String now = now();


// keep whatever is already in DB

            if (hasColumn(db, GeoDbHelper.TABLE_GROUPS, "updated_at")) up.put("updated_at", now);
            if (hasColumn(db, GeoDbHelper.TABLE_GROUPS, "timestamp")) up.put("timestamp", now);

            if (up.size() > 0) {
                db.update(GeoDbHelper.TABLE_GROUPS, up, "groupid=?", new String[]{existingId});
            }
            return existingId;
        }

        String newId = UUID.randomUUID().toString();
        String now = now();

        ContentValues cv = new ContentValues();
        cv.put("groupid", newId);
        cv.put("motherfolder", mf);
        cv.put("foldername", folderRel);
        cv.put("siteid", sid);
        cv.put("sessiondate", sd);
        cv.put("description", ""); // or wag mo na ilagay at all

        if (hasColumn(db, GeoDbHelper.TABLE_GROUPS, "created_at")) cv.put("created_at", now);
        if (hasColumn(db, GeoDbHelper.TABLE_GROUPS, "updated_at")) cv.put("updated_at", now);
        if (hasColumn(db, GeoDbHelper.TABLE_GROUPS, "timestamp")) cv.put("timestamp", now);

        long row = db.insert(GeoDbHelper.TABLE_GROUPS, null, cv);
        if (row == -1) {
            String id2 = findGroupIdByDate(db, mf, sid, sd);
            if (id2 != null) return id2;
            throw new RuntimeException("Failed to create group (insert returned -1)");
        }

        return newId;
    }

    private String findGroupIdByDate(SQLiteDatabase db, String mf, String sid, String sd) {
        Cursor c = null;
        try {
            String orderCol = null;
            if (hasColumn(db, GeoDbHelper.TABLE_GROUPS, "updated_at")) orderCol = "updated_at";
            else if (hasColumn(db, GeoDbHelper.TABLE_GROUPS, "timestamp")) orderCol = "timestamp";

            String sql =
                    "SELECT groupid FROM " + GeoDbHelper.TABLE_GROUPS + " " +
                            "WHERE motherfolder=? AND siteid=? AND sessiondate=? " +
                            (orderCol != null ? ("ORDER BY " + orderCol + " DESC ") : "") +
                            "LIMIT 1";

            c = db.rawQuery(sql, new String[]{mf, sid, sd});
            if (c.moveToFirst()) return c.getString(0);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public String getFolderNameByGroupId(String groupId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT foldername FROM " + GeoDbHelper.TABLE_GROUPS + " WHERE groupid=? LIMIT 1",
                    new String[]{safe(groupId)}
            );
            if (c.moveToFirst()) return safe(c.getString(0));
        } finally {
            if (c != null) c.close();
        }
        return "";
    }

    public String getRemarksByGroupId(String groupId) {
        groupId = safe(groupId);
        if (groupId.isEmpty()) return "";

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT description FROM " + GeoDbHelper.TABLE_GROUPS + " WHERE groupid=? LIMIT 1",
                    new String[]{groupId}
            );
            if (c.moveToFirst()) return c.isNull(0) ? "" : safe(c.getString(0));
        } finally {
            if (c != null) c.close();
        }
        return "";
    }

    public int updateRemarks(String groupId, String newRemarks) {
        groupId = safe(groupId);
        if (groupId.isEmpty()) return 0;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("description", safeRemarks(newRemarks));

        String now = now();
        if (hasColumn(db, GeoDbHelper.TABLE_GROUPS, "updated_at")) cv.put("updated_at", now);
        if (hasColumn(db, GeoDbHelper.TABLE_GROUPS, "timestamp")) cv.put("timestamp", now);

        return db.update(GeoDbHelper.TABLE_GROUPS, cv, "groupid=?", new String[]{groupId});
    }

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndexOrThrow("name"));
                if (column.equalsIgnoreCase(name)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String safe(String s) {
        return (s == null) ? "" : s.trim();
    }

    private static String safeRemarks(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.equalsIgnoreCase("GENERAL")) return "";
        return s;
    }
}