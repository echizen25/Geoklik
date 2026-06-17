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
import ph.gov.geocamera.presentation.map.PhotoPin;

public class ImageMetaRepository {

    private final GeoDbHelper dbHelper;

    public ImageMetaRepository(Context context) {
        dbHelper = new GeoDbHelper(context);
    }

    // ============================================================
    // STATUS
    // ============================================================
    public static final int STATUS_PENDING   = 0;
    public static final int STATUS_UPLOADED  = 1;
    public static final int STATUS_FAILED    = 2;
    public static final int STATUS_UPLOADING = 3;

    public static final String ERR_NO_PROJECT_FOUND = "NO_PROJECT_FOUND";

    // ============================================================
    // INSERT
    // ============================================================
    public long insertImageMeta(
            String uuid,
            String groupId,
            String siteId,
            String userId,
            Double lat,
            Double lng,
            Double accuracy,
            String locationText,
            String description,
            String errorAtLoc,
            String project,
            String filename
    ) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues cv = new ContentValues();
        cv.put("uuid", uuid);
        cv.put("groupid", groupId);

        // local tbl_imagemeta.siteid = actual server-side project_id
        cv.put("siteid", siteId);

        cv.put("userid", userId);
        cv.put("latitude", lat);
        cv.put("longitude", lng);
        cv.put("accuracy", accuracy);
        cv.put("location", locationText);
        cv.put("description", description);
        cv.put("erroratloc", errorAtLoc);

        // local tbl_imagemeta.project = funding / display value only
        cv.put("project", project);

        cv.put("filename", filename);
        cv.put("timestamp", now());
        cv.put("status", STATUS_PENDING);

        return db.insert("tbl_imagemeta", null, cv);
    }

    // ============================================================
    // STATUS UPDATES
    // ============================================================
    public void updateStatus(String uuid, int status) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        db.update("tbl_imagemeta", cv, "uuid = ?", new String[]{uuid});
    }

    public void markUploading(String uuid) {
        updateStatus(uuid, STATUS_UPLOADING);
    }

    public void markUploadSuccess(String uuid, String serverPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues cv = new ContentValues();
        cv.put("status", STATUS_UPLOADED);
        cv.put("server_path", serverPath);
        cv.put("last_sync_error", (String) null);
        cv.put("last_sync_at", now());

        db.update("tbl_imagemeta", cv, "uuid = ?", new String[]{uuid});
    }

    public void markUploadFail(String uuid, String error) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL(
                "UPDATE tbl_imagemeta " +
                        "SET status = ?, " +
                        "sync_attempts = COALESCE(sync_attempts,0)+1, " +
                        "last_sync_error = ?, " +
                        "last_sync_at = ? " +
                        "WHERE uuid = ?",
                new Object[]{STATUS_FAILED, error, now(), uuid}
        );
    }

    /**
     * Pag nag-crash ang app habang uploading, maiiwan status=3
     * Tawagin mo ito before queue upload para hindi ma-stuck.
     */
    public void resetStuckUploading() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL(
                "UPDATE tbl_imagemeta " +
                        "SET status = ?, last_sync_error = ?, last_sync_at = ? " +
                        "WHERE status = ?",
                new Object[]{STATUS_FAILED, "RECOVERED_STUCK", now(), STATUS_UPLOADING}
        );
    }

    // ============================================================
    // COUNTERS / HELPERS
    // ============================================================
    public int countBySite(String siteId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM tbl_imagemeta WHERE siteid = ?", new String[]{siteId});
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }

    /**
     * Pending for sync:
     * - include STATUS_PENDING
     * - include STATUS_FAILED except NO_PROJECT_FOUND
     */
    public int countPendingForSync() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) " +
                        "FROM tbl_imagemeta " +
                        "WHERE status = ? " +
                        "   OR (status = ? AND COALESCE(last_sync_error,'') <> ?)",
                new String[]{
                        String.valueOf(STATUS_PENDING),
                        String.valueOf(STATUS_FAILED),
                        ERR_NO_PROJECT_FOUND
                }
        );
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public int countNoProjectFoundFailed() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) " +
                        "FROM tbl_imagemeta " +
                        "WHERE status = ? AND COALESCE(last_sync_error,'') = ?",
                new String[]{
                        String.valueOf(STATUS_FAILED),
                        ERR_NO_PROJECT_FOUND
                }
        );
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public int countFailedByError(String error) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) " +
                        "FROM tbl_imagemeta " +
                        "WHERE status = ? AND COALESCE(last_sync_error,'') = ?",
                new String[]{
                        String.valueOf(STATUS_FAILED),
                        error == null ? "" : error.trim()
                }
        );
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    // ============================================================
    // FILTER SUPPORT
    // ============================================================
    public Cursor getDistinctProjects() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        return db.rawQuery(
                "SELECT DISTINCT project FROM tbl_imagemeta " +
                        "WHERE project IS NOT NULL AND project <> '' " +
                        "ORDER BY project",
                null
        );
    }

    public Cursor getDistinctYears() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        return db.rawQuery(
                "SELECT DISTINCT strftime('%Y', timestamp) AS y " +
                        "FROM tbl_imagemeta " +
                        "WHERE timestamp IS NOT NULL AND timestamp <> '' " +
                        "ORDER BY y DESC",
                null
        );
    }

    public Cursor getDistinctYearsForSite(String siteId, String projectOrAll) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        boolean filterProject = projectOrAll != null && !"ALL".equalsIgnoreCase(projectOrAll);

        ArrayList<String> args = new ArrayList<>();
        args.add(siteId);
        if (filterProject) args.add(projectOrAll);

        String sql =
                "SELECT DISTINCT substr(g.sessiondate, 1, 4) AS y " +
                        "FROM tbl_groups g " +
                        "WHERE g.siteid = ? " +
                        "AND EXISTS (" +
                        "  SELECT 1 FROM tbl_imagemeta t " +
                        "  WHERE t.groupid = g.groupid " +
                        (filterProject ? " AND t.project = ? " : "") +
                        ") " +
                        "ORDER BY y DESC";

        return db.rawQuery(sql, args.toArray(new String[0]));
    }

    public Cursor getDistinctMonthsForSite(String siteId, String projectOrAll, String yearOrAll) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        boolean filterProject = projectOrAll != null && !"ALL".equalsIgnoreCase(projectOrAll);
        boolean filterYear = yearOrAll != null && !"ALL".equalsIgnoreCase(yearOrAll);

        ArrayList<String> args = new ArrayList<>();
        args.add(siteId);
        if (filterYear) args.add(yearOrAll);
        if (filterProject) args.add(projectOrAll);

        String sql =
                "SELECT DISTINCT substr(g.sessiondate, 6, 2) AS m " +
                        "FROM tbl_groups g " +
                        "WHERE g.siteid = ? " +
                        (filterYear ? " AND substr(g.sessiondate, 1, 4) = ? " : "") +
                        "AND EXISTS (" +
                        "  SELECT 1 FROM tbl_imagemeta t " +
                        "  WHERE t.groupid = g.groupid " +
                        (filterProject ? " AND t.project = ? " : "") +
                        ") " +
                        "ORDER BY m ASC";

        return db.rawQuery(sql, args.toArray(new String[0]));
    }

    // ============================================================
    // ROOT SITE CARDS
    // ============================================================
    public Cursor getRootSiteCards(String projectOrAll, String yearOrAll, String search) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        boolean filterProject = projectOrAll != null && !"ALL".equalsIgnoreCase(projectOrAll);
        boolean filterYear = yearOrAll != null && !"ALL".equalsIgnoreCase(yearOrAll);
        boolean hasSearch = search != null && !search.trim().isEmpty();

        String q = hasSearch ? "%" + search.trim() + "%" : null;

        StringBuilder where = new StringBuilder(" im.siteid IS NOT NULL AND trim(im.siteid) <> '' ");
        ArrayList<String> args = new ArrayList<>();

        if (filterProject) {
            where.append(" AND im.project = ? ");
            args.add(projectOrAll);
        }

        if (filterYear) {
            where.append(" AND strftime('%Y', im.timestamp) = ? ");
            args.add(yearOrAll);
        }

        if (hasSearch) {
            where.append(
                    " AND (" +
                            "im.siteid LIKE ? " +
                            "OR COALESCE(s.siteid,'') LIKE ? " +
                            "OR COALESCE(s.code,'') LIKE ? " +
                            "OR COALESCE(s.name,'') LIKE ? " +
                            "OR COALESCE(p.projectid,'') LIKE ? " +
                            "OR COALESCE(p.code,'') LIKE ? " +
                            "OR COALESCE(p.coda,'') LIKE ? " +
                            "OR COALESCE(im.project,'') LIKE ? " +
                            ") "
            );
            args.add(q);
            args.add(q);
            args.add(q);
            args.add(q);
            args.add(q);
            args.add(q);
            args.add(q);
            args.add(q);
        }

        String sql =
                "SELECT " +
                        "im.siteid AS siteid, " +                                                       // 0
                        "COUNT(*) AS totalPhotos, " +                                                   // 1
                        "SUM(CASE WHEN im.status = " + STATUS_UPLOADED + " THEN 1 ELSE 0 END) AS syncedPhotos, " +   // 2
                        "SUM(CASE WHEN im.status != " + STATUS_UPLOADED + " THEN 1 ELSE 0 END) AS unsyncedPhotos, " + // 3
                        "MAX(im.timestamp) AS lastUpdated, " +                                          // 4

                        "(SELECT x.filename FROM tbl_imagemeta x " +
                        " WHERE x.siteid = im.siteid " +
                        (filterProject ? " AND x.project = ? " : "") +
                        (filterYear ? " AND strftime('%Y', x.timestamp) = ? " : "") +
                        " ORDER BY x.timestamp DESC LIMIT 1) AS latestFilename, " +                     // 5

                        "(SELECT x.timestamp FROM tbl_imagemeta x " +
                        " WHERE x.siteid = im.siteid " +
                        (filterProject ? " AND x.project = ? " : "") +
                        (filterYear ? " AND strftime('%Y', x.timestamp) = ? " : "") +
                        " ORDER BY x.timestamp DESC LIMIT 1) AS latestTimestamp, " +                    // 6

                        "COALESCE(NULLIF(trim(s.name), ''), im.siteid) AS siteName, " +                 // 7

                        "COALESCE(" +
                        " NULLIF(trim(p.coda), ''), " +
                        " NULLIF(trim(im.project), ''), " +
                        " NULLIF(trim(s.name), ''), " +
                        " NULLIF(trim(p.code), ''), " +
                        " '—' " +
                        ") AS projectLabel, " +                                                          // 8

                        "COALESCE(" +
                        " NULLIF(trim((SELECT x.location FROM tbl_imagemeta x " +
                        "   WHERE x.siteid = im.siteid " +
                        (filterProject ? " AND x.project = ? " : "") +
                        (filterYear ? " AND strftime('%Y', x.timestamp) = ? " : "") +
                        "   ORDER BY x.timestamp DESC LIMIT 1)), ''), " +
                        " '-' " +
                        ") AS latestLocation, " +                                                        // 9

                        "SUM(CASE WHEN im.status = " + STATUS_PENDING + " THEN 1 ELSE 0 END) AS pendingCount, " +     // 10
                        "SUM(CASE WHEN im.status = " + STATUS_UPLOADING + " THEN 1 ELSE 0 END) AS uploadingCount, " + // 11
                        "SUM(CASE WHEN im.status = " + STATUS_FAILED + " THEN 1 ELSE 0 END) AS failedCount, " +       // 12

                        "COALESCE(" +
                        " NULLIF(trim(p.code), ''), " +
                        " NULLIF(trim(s.code), ''), " +
                        " im.siteid " +
                        ") AS displayCode " +                                                            // 13

                        "FROM tbl_imagemeta im " +
                        "LEFT JOIN tbl_site s ON s.siteid = im.siteid " +
                        "LEFT JOIN tbl_projects p ON p.projectid = s.projectid " +
                        "WHERE " + where +
                        "GROUP BY im.siteid, s.name, s.code, p.projectid, p.code, p.coda " +
                        "ORDER BY lastUpdated DESC";

        ArrayList<String> finalArgs = new ArrayList<>(args);

        // latestFilename subquery
        if (filterProject) finalArgs.add(projectOrAll);
        if (filterYear) finalArgs.add(yearOrAll);

        // latestTimestamp subquery
        if (filterProject) finalArgs.add(projectOrAll);
        if (filterYear) finalArgs.add(yearOrAll);

        // latestLocation subquery
        if (filterProject) finalArgs.add(projectOrAll);
        if (filterYear) finalArgs.add(yearOrAll);

        return db.rawQuery(sql, finalArgs.toArray(new String[0]));
    }

    public Cursor getRootSiteCards(String projectOrAll, String yearOrAll) {
        return getRootSiteCards(projectOrAll, yearOrAll, null);
    }

    // ============================================================
    // GROUP -> IMAGES GRID
    // ============================================================
    public Cursor getImagesForGroup(String groupId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        return db.rawQuery(
                "SELECT uuid, filename, timestamp, status " +
                        "FROM tbl_imagemeta " +
                        "WHERE groupid = ? " +
                        "ORDER BY timestamp DESC",
                new String[]{groupId}
        );
    }

    // ============================================================
    // DELETE
    // ============================================================
    public int deleteImageByUuid(String uuid) {
        if (uuid == null) return 0;
        uuid = uuid.trim();
        if (uuid.isEmpty()) return 0;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(GeoDbHelper.TABLE_IMAGEMETA, "uuid = ?", new String[]{uuid});
    }

    // ============================================================
    // EXPORT
    // ============================================================
    public Cursor getImagesForExportBySites(List<String> siteIds, String projectOrAll, String yearOrAll) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        if (siteIds == null || siteIds.isEmpty()) {
            return db.rawQuery(
                    "SELECT im.uuid, im.project, im.siteid, im.filename, " +
                            "g.motherfolder, g.sessiondate, g.description " +
                            "FROM tbl_imagemeta im " +
                            "JOIN tbl_groups g ON g.groupid = im.groupid " +
                            "WHERE 0=1",
                    null
            );
        }

        boolean filterProject = projectOrAll != null && !"ALL".equalsIgnoreCase(projectOrAll);
        boolean filterYear = yearOrAll != null && !"ALL".equalsIgnoreCase(yearOrAll);

        StringBuilder in = new StringBuilder();
        for (int i = 0; i < siteIds.size(); i++) {
            if (i > 0) in.append(",");
            in.append("?");
        }

        StringBuilder where = new StringBuilder(" im.siteid IN (" + in + ") ");
        ArrayList<String> args = new ArrayList<>(siteIds);

        if (filterProject) {
            where.append(" AND im.project = ? ");
            args.add(projectOrAll);
        }
        if (filterYear) {
            where.append(" AND substr(g.sessiondate, 1, 4) = ? ");
            args.add(yearOrAll);
        }

        String sql =
                "SELECT im.uuid, im.project, im.siteid, im.filename, " +
                        "g.motherfolder, g.sessiondate, im.description " +
                        "FROM tbl_imagemeta im " +
                        "JOIN tbl_groups g ON g.groupid = im.groupid " +
                        "WHERE " + where +
                        "ORDER BY g.sessiondate DESC, im.timestamp DESC";

        return db.rawQuery(sql, args.toArray(new String[0]));
    }

    // ============================================================
    // DATES FOR SITE
    // ============================================================
    public Cursor getDatesForSite(String siteId, String projectOrAll, String yearOrAll, String monthOrAll) {

        SQLiteDatabase db = dbHelper.getReadableDatabase();

        boolean filterProject = projectOrAll != null && !"ALL".equalsIgnoreCase(projectOrAll);
        boolean filterYear    = yearOrAll != null && !"ALL".equalsIgnoreCase(yearOrAll);
        boolean filterMonth   = monthOrAll != null && !"ALL".equalsIgnoreCase(monthOrAll);

        StringBuilder where = new StringBuilder(" g.siteid = ? ");
        ArrayList<String> args = new ArrayList<>();
        args.add(siteId);

        if (filterYear) {
            where.append(" AND substr(g.sessiondate, 1, 4) = ? ");
            args.add(yearOrAll);
        }

        if (filterMonth) {
            where.append(" AND substr(g.sessiondate, 6, 2) = ? ");
            args.add(monthOrAll);
        }

        if (filterProject) {
            where.append(" AND t.project = ? ");
            args.add(projectOrAll);
        }

        String sql =
                "SELECT " +
                        "g.sessiondate AS sessiondate, " +
                        "g.groupid AS groupid, " +
                        "g.description AS remarks, " +
                        "COUNT(t.uuid) AS totalPhotos, " +
                        "SUM(CASE WHEN t.status = " + STATUS_UPLOADED + " THEN 1 ELSE 0 END) AS uploadedPhotos, " +
                        "SUM(CASE WHEN t.status = " + STATUS_UPLOADING + " THEN 1 ELSE 0 END) AS uploadingPhotos, " +
                        "SUM(CASE WHEN t.status = " + STATUS_FAILED + " THEN 1 ELSE 0 END) AS failedPhotos, " +
                        "SUM(CASE WHEN t.status = " + STATUS_PENDING + " THEN 1 ELSE 0 END) AS pendingPhotos, " +
                        "SUM(CASE WHEN t.status <> " + STATUS_UPLOADED + " THEN 1 ELSE 0 END) AS unsyncedPhotos, " +
                        "(SELECT x.filename FROM tbl_imagemeta x " +
                        " WHERE x.groupid = g.groupid " +
                        " ORDER BY x.timestamp DESC LIMIT 1) AS latestFilename, " +
                        "(SELECT x.timestamp FROM tbl_imagemeta x " +
                        " WHERE x.groupid = g.groupid " +
                        " ORDER BY x.timestamp DESC LIMIT 1) AS latestTimestamp " +
                        "FROM (" +
                        "   SELECT gg.* FROM tbl_groups gg " +
                        "   JOIN (" +
                        "       SELECT siteid, sessiondate, MAX(updated_at) AS mx " +
                        "       FROM tbl_groups " +
                        "       GROUP BY siteid, sessiondate" +
                        "   ) pick ON pick.siteid = gg.siteid AND pick.sessiondate = gg.sessiondate AND pick.mx = gg.updated_at" +
                        ") g " +
                        "INNER JOIN tbl_imagemeta t ON t.groupid = g.groupid " +
                        "WHERE " + where +
                        "GROUP BY g.groupid, g.sessiondate, g.description " +
                        "HAVING COUNT(t.uuid) > 0 " +
                        "ORDER BY g.sessiondate DESC";

        return db.rawQuery(sql, args.toArray(new String[0]));
    }

    // ============================================================
    // SITE -> DATE -> GROUPS
    // ============================================================
    public Cursor getDescriptionsForSiteDate(String siteId, String sessionDate, String projectOrAll) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        boolean filterProject = projectOrAll != null && !"ALL".equalsIgnoreCase(projectOrAll);

        String sql =
                "SELECT g.groupid, g.description, " +
                        "COUNT(*) AS totalPhotos, " +
                        "SUM(CASE WHEN t.status = 1 THEN 1 ELSE 0 END) AS syncedPhotos, " +
                        "(SELECT x.filename FROM tbl_imagemeta x WHERE x.groupid = g.groupid " +
                        (filterProject ? " AND x.project = ? " : "") +
                        " ORDER BY x.timestamp DESC LIMIT 1) AS latestFilename, " +
                        "(SELECT x.timestamp FROM tbl_imagemeta x WHERE x.groupid = g.groupid " +
                        (filterProject ? " AND x.project = ? " : "") +
                        " ORDER BY x.timestamp DESC LIMIT 1) AS latestTimestamp " +
                        "FROM tbl_groups g " +
                        "JOIN tbl_imagemeta t ON t.groupid = g.groupid " +
                        "WHERE g.siteid = ? AND g.sessiondate = ? " +
                        (filterProject ? " AND t.project = ? " : "") +
                        "GROUP BY g.groupid, g.description " +
                        "ORDER BY latestTimestamp DESC";

        ArrayList<String> args = new ArrayList<>();
        if (filterProject) args.add(projectOrAll);
        if (filterProject) args.add(projectOrAll);
        args.add(siteId);
        args.add(sessionDate);
        if (filterProject) args.add(projectOrAll);

        return db.rawQuery(sql, args.toArray(new String[0]));
    }

    // ============================================================
    // SINGLE GROUP FOR SITE + DATE
    // ============================================================
    public String getGroupIdForSiteDate(String siteId, String sessionDate, String projectOrAll) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        boolean filterProject = projectOrAll != null && !"ALL".equalsIgnoreCase(projectOrAll);

        String sql =
                "SELECT g.groupid " +
                        "FROM tbl_groups g " +
                        "JOIN tbl_imagemeta t ON t.groupid = g.groupid " +
                        "WHERE g.siteid = ? AND g.sessiondate = ? " +
                        (filterProject ? " AND t.project = ? " : "") +
                        "GROUP BY g.groupid " +
                        "ORDER BY MAX(t.timestamp) DESC " +
                        "LIMIT 1";

        Cursor c = null;
        try {
            if (filterProject) {
                c = db.rawQuery(sql, new String[]{siteId, sessionDate, projectOrAll});
            } else {
                c = db.rawQuery(sql, new String[]{siteId, sessionDate});
            }

            if (c.moveToFirst()) return c.getString(0);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // ============================================================
    // REMARKS
    // ============================================================
    public int updateGroupRemarksForSiteDate(String siteId, String sessionDate, String newRemarks) {
        String gid = getLatestGroupIdForSiteDate(siteId, sessionDate);
        if (gid == null || gid.trim().isEmpty()) return 0;

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues cv = new ContentValues();
        cv.put("description", newRemarks == null ? "" : newRemarks.trim());

        String now = now();

        if (hasColumn(db, "tbl_groups", "updated_at")) {
            cv.put("updated_at", now);
        }

        if (hasColumn(db, "tbl_groups", "timestamp")) {
            cv.put("timestamp", now);
        }

        return db.update("tbl_groups", cv, "groupid=?", new String[]{gid});
    }

    public String getLatestGroupIdForSiteDate(String siteId, String sessionDate) {
        Cursor c = null;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();

            String orderCol = hasColumn(db, "tbl_groups", "updated_at") ? "updated_at"
                    : (hasColumn(db, "tbl_groups", "timestamp") ? "timestamp" : null);

            String sql;
            if (orderCol != null) {
                sql = "SELECT groupid FROM tbl_groups " +
                        "WHERE siteid=? AND sessiondate=? " +
                        "ORDER BY " + orderCol + " DESC LIMIT 1";
            } else {
                sql = "SELECT groupid FROM tbl_groups " +
                        "WHERE siteid=? AND sessiondate=? LIMIT 1";
            }

            c = db.rawQuery(sql, new String[]{siteId, sessionDate});
            if (c.moveToFirst()) return c.getString(0);
            return null;

        } finally {
            if (c != null) c.close();
        }
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

    public PhotoPin getPhotoPinByUuid(String uuid) {
        if (uuid == null) return null;
        uuid = uuid.trim();
        if (uuid.isEmpty()) return null;

        Cursor c = null;

        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();

            c = db.rawQuery(
                    "SELECT uuid, latitude, longitude, filename, timestamp, siteid " +
                            "FROM " + GeoDbHelper.TABLE_IMAGEMETA + " " +
                            "WHERE uuid=? LIMIT 1",
                    new String[]{uuid}
            );

            if (c != null && c.moveToFirst()) {
                String u = c.getString(0);
                double lat = c.isNull(1) ? 0 : c.getDouble(1);
                double lng = c.isNull(2) ? 0 : c.getDouble(2);
                String filename = c.isNull(3) ? "" : c.getString(3);
                String ts = c.isNull(4) ? "" : c.getString(4);
                String siteid = c.isNull(5) ? "" : c.getString(5);

                PhotoPin p = new PhotoPin();
                p.uuid = u;
                p.lat = lat;
                p.lng = lng;
                p.filename = filename;
                p.title = (siteid.trim().isEmpty()) ? "Photo" : siteid.trim();
                p.subtitle = ts;

                android.util.Log.d("ImageMetaRepo",
                        "getPhotoPinByUuid ok uuid=" + u + " lat=" + lat + " lng=" + lng + " file=" + filename);

                return p;
            } else {
                android.util.Log.d("ImageMetaRepo", "getPhotoPinByUuid NOT FOUND uuid=" + uuid);
            }

        } catch (Exception e) {
            android.util.Log.e("ImageMetaRepo", "getPhotoPinByUuid ERROR uuid=" + uuid, e);
        } finally {
            if (c != null) c.close();
        }

        return null;
    }

    // ============================================================
    // PENDING UPLOADS
    // ============================================================
    public Cursor getPendingUploads(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        return db.rawQuery(
                "SELECT " +
                        "im.uuid, " +                                        // 0
                        "im.filename, " +                                    // 1
                        "COALESCE(im.project, '') AS project, " +            // 2 funding/display only
                        "COALESCE(im.siteid, '') AS siteId, " +              // 3 actual server project_id
                        "im.userid, " +                                      // 4
                        "im.groupid, " +                                     // 5
                        "COALESCE(im.project, '') AS fundingCode, " +        // 6 funding/display only
                        "im.latitude, " +                                    // 7
                        "im.longitude, " +                                   // 8
                        "im.accuracy, " +                                    // 9
                        "im.location, " +                                    // 10
                        "im.erroratloc, " +                                  // 11
                        "im.description, " +                                 // 12
                        "im.timestamp, " +                                   // 13
                        "g.sessiondate, " +                                  // 14
                        "g.foldername, " +                                   // 15
                        "COALESCE(g.updated_at, g.timestamp, im.timestamp, '') AS progressTimestamp, " + // 16
                        "COALESCE(g.description, '') AS groupRemarks " +     // 17
                        "FROM tbl_imagemeta im " +
                        "JOIN tbl_groups g ON g.groupid = im.groupid " +
                        "WHERE im.status = ? " +
                        "   OR (im.status = ? AND COALESCE(im.last_sync_error,'') <> ?) " +
                        "ORDER BY im.timestamp ASC " +
                        "LIMIT ?",
                new String[]{
                        String.valueOf(STATUS_PENDING),
                        String.valueOf(STATUS_FAILED),
                        ERR_NO_PROJECT_FOUND,
                        String.valueOf(limit)
                }
        );
    }

    public String getGroupRemarksForSiteDate(String siteId, String sessionDate) {
        Cursor c = null;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();

            String orderCol = hasColumn(db, "tbl_groups", "updated_at") ? "updated_at"
                    : (hasColumn(db, "tbl_groups", "timestamp") ? "timestamp" : null);

            String sql;
            if (orderCol != null) {
                sql = "SELECT description FROM tbl_groups " +
                        "WHERE siteid=? AND sessiondate=? " +
                        "ORDER BY " + orderCol + " DESC LIMIT 1";
            } else {
                sql = "SELECT description FROM tbl_groups " +
                        "WHERE siteid=? AND sessiondate=? LIMIT 1";
            }

            c = db.rawQuery(sql, new String[]{siteId, sessionDate});
            if (c.moveToFirst()) return c.isNull(0) ? "" : c.getString(0);
            return "";
        } finally {
            if (c != null) c.close();
        }
    }

    public String getFilenameByUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT filename FROM " + GeoDbHelper.TABLE_IMAGEMETA + " WHERE uuid=? LIMIT 1",
                    new String[]{uuid.trim()}
            );
            if (c.moveToFirst()) return c.getString(0);
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    public int countFailedByErrorForSite(String siteId, String error) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) " +
                        "FROM tbl_imagemeta " +
                        "WHERE siteid = ? " +
                        "AND status = ? " +
                        "AND COALESCE(last_sync_error,'') = ?",
                new String[]{
                        siteId == null ? "" : siteId.trim(),
                        String.valueOf(STATUS_FAILED),
                        error == null ? "" : error.trim()
                }
        );
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public int deleteImagesByGroupId(String groupId) {
        if (groupId == null || groupId.trim().isEmpty()) return 0;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(GeoDbHelper.TABLE_IMAGEMETA, "groupid=?", new String[]{groupId.trim()});
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}