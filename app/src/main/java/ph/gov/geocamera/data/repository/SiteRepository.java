package ph.gov.geocamera.data.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.data.local.db.GeoDbHelper;

public class SiteRepository {

    private final GeoDbHelper dbHelper;

    public SiteRepository(Context context) {
        dbHelper = new GeoDbHelper(context);
    }

    public List<String> getAllSiteIds(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<String> list = new ArrayList<>();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT siteid " +
                            "FROM tbl_site " +
                            "ORDER BY timestamp DESC, siteid ASC " +
                            "LIMIT ?",
                    new String[]{String.valueOf(limit)}
            );

            while (c.moveToNext()) {
                String s = c.getString(0);
                if (s != null && !s.trim().isEmpty()) list.add(s.trim());
            }
        } finally {
            if (c != null) c.close();
        }

        return list;
    }

    public List<String> searchSiteIds(String query, int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<String> list = new ArrayList<>();
        Cursor c = null;

        if (query == null) query = "";
        query = query.trim();

        try {
            c = db.rawQuery(
                    "SELECT siteid " +
                            "FROM tbl_site " +
                            "WHERE siteid LIKE ? " +
                            "   OR COALESCE(code,'') LIKE ? " +
                            "   OR COALESCE(name,'') LIKE ? " +
                            "ORDER BY timestamp DESC, siteid ASC " +
                            "LIMIT ?",
                    new String[]{
                            "%" + query + "%",
                            "%" + query + "%",
                            "%" + query + "%",
                            String.valueOf(limit)
                    }
            );

            while (c.moveToNext()) {
                String s = c.getString(0);
                if (s != null && !s.trim().isEmpty()) list.add(s.trim());
            }
        } finally {
            if (c != null) c.close();
        }

        return list;
    }

    /**
     * For nicer dropdown display:
     * SITEID • PROJECT_CODA
     */
    public List<String> getAllSiteSuggestions(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<String> list = new ArrayList<>();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT " +
                            "CASE " +
                            " WHEN COALESCE(trim(p.coda),'') <> '' THEN s.siteid || ' • ' || p.coda " +
                            " WHEN COALESCE(trim(s.name),'') <> '' THEN s.siteid || ' • ' || s.name " +
                            " ELSE s.siteid " +
                            "END AS display_text " +
                            "FROM tbl_site s " +
                            "LEFT JOIN tbl_projects p ON p.projectid = s.projectid " +
                            "ORDER BY s.timestamp DESC, s.siteid ASC " +
                            "LIMIT ?",
                    new String[]{String.valueOf(limit)}
            );

            while (c.moveToNext()) {
                String s = c.getString(0);
                if (s != null && !s.trim().isEmpty()) list.add(s.trim());
            }
        } finally {
            if (c != null) c.close();
        }

        return list;
    }

    /**
     * Search by:
     * - siteid
     * - site code/name
     * - linked project coda/code/projectid
     */
    public List<String> searchSiteSuggestions(String query, int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<String> list = new ArrayList<>();
        Cursor c = null;

        if (query == null) query = "";
        query = query.trim();

        try {
            if (query.isEmpty()) {
                return getAllSiteSuggestions(limit);
            }

            String like = "%" + query + "%";

            c = db.rawQuery(
                    "SELECT " +
                            "CASE " +
                            " WHEN COALESCE(trim(p.coda),'') <> '' THEN s.siteid || ' • ' || p.coda " +
                            " WHEN COALESCE(trim(s.name),'') <> '' THEN s.siteid || ' • ' || s.name " +
                            " ELSE s.siteid " +
                            "END AS display_text " +
                            "FROM tbl_site s " +
                            "LEFT JOIN tbl_projects p ON p.projectid = s.projectid " +
                            "WHERE s.siteid LIKE ? " +
                            "   OR COALESCE(s.code,'') LIKE ? " +
                            "   OR COALESCE(s.name,'') LIKE ? " +
                            "   OR COALESCE(s.projectid,'') LIKE ? " +
                            "   OR COALESCE(p.coda,'') LIKE ? " +
                            "   OR COALESCE(p.code,'') LIKE ? " +
                            "ORDER BY s.timestamp DESC, s.siteid ASC " +
                            "LIMIT ?",
                    new String[]{
                            like, like, like, like, like, like,
                            String.valueOf(limit)
                    }
            );

            while (c.moveToNext()) {
                String s = c.getString(0);
                if (s != null && !s.trim().isEmpty()) list.add(s.trim());
            }
        } finally {
            if (c != null) c.close();
        }

        return list;
    }

    /**
     * Accepts:
     * - raw siteid
     * - display format "SITEID • LABEL"
     */
    public String resolveSiteId(String rawInput) {
        if (rawInput == null) return null;

        String input = rawInput.trim();
        if (input.isEmpty()) return null;

        int sep = input.indexOf("•");
        if (sep > 0) {
            input = input.substring(0, sep).trim();
        }

        if (exists(input)) return input;
        return null;
    }

    // ============================================================
    // CREATE / UPSERT SITE
    // ============================================================
    public boolean getOrCreateSite(String siteId,
                                   String imeiOrDeviceId,
                                   String codeOptional,
                                   String nameOptional) {
        return upsertSite(siteId, null, imeiOrDeviceId, codeOptional, nameOptional);
    }

    public boolean upsertSite(String siteId,
                              String projectId,
                              String imeiOrDeviceId,
                              String codeOptional,
                              String nameOptional) {

        if (siteId == null) return false;
        siteId = siteId.trim();
        if (siteId.isEmpty()) return false;

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues cv = new ContentValues();
        cv.put("siteid", siteId);

        if (projectId != null && !projectId.trim().isEmpty()) {
            cv.put("projectid", projectId.trim());
        }

        if (codeOptional != null) cv.put("code", codeOptional.trim());
        if (imeiOrDeviceId != null) cv.put("imei", imeiOrDeviceId.trim());
        if (nameOptional != null) cv.put("name", nameOptional.trim());

        cv.put("timestamp", now());

        if (!exists(siteId)) {
            cv.put("imageq", 0);
            cv.put("inprogress", 0);
            long r = db.insert("tbl_site", null, cv);
            return r != -1;
        } else {
            int r = db.update("tbl_site", cv, "siteid = ?", new String[]{siteId});
            return r > 0;
        }
    }

    public boolean updateSiteProject(String siteId, String projectId) {
        if (siteId == null || projectId == null) return false;

        siteId = siteId.trim();
        projectId = projectId.trim();

        if (siteId.isEmpty() || projectId.isEmpty()) return false;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("projectid", projectId);
        cv.put("timestamp", now());

        int r = db.update("tbl_site", cv, "siteid = ?", new String[]{siteId});
        return r > 0;
    }

    public boolean exists(String siteId) {
        if (siteId == null) return false;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT siteid FROM tbl_site WHERE siteid = ? LIMIT 1",
                    new String[]{siteId.trim()}
            );
            return c.moveToFirst();
        } finally {
            if (c != null) c.close();
        }
    }

    // ============================================================
    // SITE -> PROJECT LOOKUPS
    // ============================================================
    public String getProjectIdBySiteId(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT projectid " +
                            "FROM tbl_site " +
                            "WHERE siteid = ? " +
                            "LIMIT 1",
                    new String[]{siteId.trim()}
            );

            if (c.moveToFirst()) {
                String s = c.isNull(0) ? null : c.getString(0);
                return (s == null || s.trim().isEmpty()) ? null : s.trim();
            }
        } finally {
            if (c != null) c.close();
        }

        return null;
    }

    public String getProjectCodaBySiteId(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT p.coda " +
                            "FROM tbl_site s " +
                            "LEFT JOIN tbl_projects p ON p.projectid = s.projectid " +
                            "WHERE s.siteid = ? " +
                            "LIMIT 1",
                    new String[]{siteId.trim()}
            );

            if (c.moveToFirst()) {
                String s = c.isNull(0) ? null : c.getString(0);
                return (s == null || s.trim().isEmpty()) ? null : s.trim();
            }
        } finally {
            if (c != null) c.close();
        }

        return null;
    }

    public String getProjectCodeBySiteId(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT p.code " +
                            "FROM tbl_site s " +
                            "LEFT JOIN tbl_projects p ON p.projectid = s.projectid " +
                            "WHERE s.siteid = ? " +
                            "LIMIT 1",
                    new String[]{siteId.trim()}
            );

            if (c.moveToFirst()) {
                String s = c.isNull(0) ? null : c.getString(0);
                return (s == null || s.trim().isEmpty()) ? null : s.trim();
            }
        } finally {
            if (c != null) c.close();
        }

        return null;
    }

    public String getProjectLabelBySiteId(String siteId) {
        String coda = getProjectCodaBySiteId(siteId);
        if (coda != null && !coda.trim().isEmpty()) return coda.trim();

        String code = getProjectCodeBySiteId(siteId);
        if (code != null && !code.trim().isEmpty()) return code.trim();

        String pid = getProjectIdBySiteId(siteId);
        if (pid != null && !pid.trim().isEmpty()) return pid.trim();

        return null;
    }

    public String getDisplayLabelBySiteId(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) return null;

        String projLabel = getProjectLabelBySiteId(siteId);
        if (projLabel != null && !projLabel.trim().isEmpty()) {
            return siteId.trim() + " • " + projLabel.trim();
        }

        return siteId.trim();
    }

    // ============================================================
    // UPDATE IMAGE COUNT (safe)
    // ============================================================
    public void updateImageCount(String siteId, int newCount) {
        if (siteId == null) return;

        siteId = siteId.trim();
        if (siteId.isEmpty()) return;

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues cv = new ContentValues();
        cv.put("imageq", Math.max(0, newCount));
        cv.put("inprogress", newCount > 0 ? 1 : 0);
        cv.put("timestamp", now());

        db.update("tbl_site", cv, "siteid = ?", new String[]{siteId});
    }

    public int getImageCount(String siteId) {
        if (siteId == null) return 0;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT imageq FROM tbl_site WHERE siteid = ? LIMIT 1",
                    new String[]{siteId.trim()}
            );

            if (c.moveToFirst()) return c.isNull(0) ? 0 : c.getInt(0);
        } finally {
            if (c != null) c.close();
        }

        return 0;
    }

    public String getLatestSiteId() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT siteid FROM tbl_site ORDER BY timestamp DESC LIMIT 1",
                    null
            );

            if (c.moveToFirst()) return c.getString(0);
        } finally {
            if (c != null) c.close();
        }

        return null;
    }

    public String getSiteTimestamp(String siteId) {
        if (siteId == null) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT timestamp FROM tbl_site WHERE siteid = ? LIMIT 1",
                    new String[]{siteId.trim()}
            );

            if (c.moveToFirst()) return c.isNull(0) ? null : c.getString(0);
        } finally {
            if (c != null) c.close();
        }

        return null;
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}