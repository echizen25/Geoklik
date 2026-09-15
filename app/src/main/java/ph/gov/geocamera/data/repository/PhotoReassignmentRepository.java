package ph.gov.geocamera.data.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ph.gov.geocamera.data.local.db.GeoDbHelper;

/**
 * Moves unsynced Infrastructure photos to another verified Infrastructure project.
 *
 * Important invariants:
 * - the original captured image file is never modified;
 * - funding/display metadata in tbl_imagemeta.project is preserved;
 * - monitoring_type remains INFRA;
 * - synced/uploading photos are never moved;
 * - moved photos are reset to PENDING and placed in the target project's group
 *   for the same capture date/remarks.
 */
public final class PhotoReassignmentRepository {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_UPLOADED = 1;
    private static final int STATUS_UPLOADING = 3;

    private final GeoDbHelper dbHelper;

    public PhotoReassignmentRepository(Context context) {
        dbHelper = new GeoDbHelper(context.getApplicationContext());
    }

    public int moveInfraPhotos(List<String> uuids, String targetProjectId) {
        if (uuids == null || uuids.isEmpty()) return 0;

        String target = clean(targetProjectId);
        if (target.isEmpty()) return 0;

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        int moved = 0;

        try {
            for (String rawUuid : uuids) {
                String uuid = clean(rawUuid);
                if (uuid.isEmpty()) continue;

                MoveInfo info = loadMoveInfo(db, uuid);
                if (info == null) continue;
                if (info.status == STATUS_UPLOADED || info.status == STATUS_UPLOADING) continue;
                if (!"INFRA".equalsIgnoreCase(clean(info.monitoringType))) continue;
                if (target.equalsIgnoreCase(clean(info.siteId))) continue;

                String sessionDate = clean(info.sessionDate);
                if (sessionDate.isEmpty()) sessionDate = dateOnly(info.timestamp);
                if (sessionDate.isEmpty()) sessionDate = today();

                String motherFolder = clean(info.motherFolder);
                if (motherFolder.isEmpty()) motherFolder = "PROJECT_0000";

                String targetGroupId = getOrCreateTargetGroup(
                        db,
                        motherFolder,
                        target,
                        sessionDate,
                        clean(info.groupRemarks)
                );

                ContentValues cv = new ContentValues();
                cv.put("siteid", target);
                cv.put("groupid", targetGroupId);
                cv.put("monitoring_type", "INFRA");
                cv.putNull("activity_project_id");
                cv.put("status", STATUS_PENDING);
                cv.put("sync_attempts", 0);
                cv.putNull("last_sync_error");
                cv.putNull("server_path");
                cv.put("last_sync_at", now());

                // Deliberately do not change tbl_imagemeta.project. In the current
                // schema it is the original funding/display value, not project_id.
                int rows = db.update(
                        GeoDbHelper.TABLE_IMAGEMETA,
                        cv,
                        "uuid=?",
                        new String[]{uuid}
                );
                moved += Math.max(0, rows);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }

        return moved;
    }

    private MoveInfo loadMoveInfo(SQLiteDatabase db, String uuid) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT " +
                            "im.siteid, " +
                            "im.timestamp, " +
                            "im.status, " +
                            "COALESCE(im.monitoring_type,'INFRA'), " +
                            "COALESCE(g.sessiondate,''), " +
                            "COALESCE(g.motherfolder,''), " +
                            "COALESCE(g.description,'') " +
                            "FROM " + GeoDbHelper.TABLE_IMAGEMETA + " im " +
                            "LEFT JOIN " + GeoDbHelper.TABLE_GROUPS + " g ON g.groupid=im.groupid " +
                            "WHERE im.uuid=? LIMIT 1",
                    new String[]{uuid}
            );

            if (!c.moveToFirst()) return null;

            MoveInfo info = new MoveInfo();
            info.siteId = c.isNull(0) ? "" : c.getString(0);
            info.timestamp = c.isNull(1) ? "" : c.getString(1);
            info.status = c.isNull(2) ? STATUS_PENDING : c.getInt(2);
            info.monitoringType = c.isNull(3) ? "INFRA" : c.getString(3);
            info.sessionDate = c.isNull(4) ? "" : c.getString(4);
            info.motherFolder = c.isNull(5) ? "" : c.getString(5);
            info.groupRemarks = c.isNull(6) ? "" : c.getString(6);
            return info;
        } finally {
            if (c != null) c.close();
        }
    }

    private String getOrCreateTargetGroup(SQLiteDatabase db,
                                          String motherFolder,
                                          String targetProjectId,
                                          String sessionDate,
                                          String remarks) {
        String existing = findExactGroup(db, motherFolder, targetProjectId, sessionDate);
        if (!existing.isEmpty()) return existing;

        String groupId = UUID.randomUUID().toString();
        String now = now();
        String folderRel = motherFolder + "/" + targetProjectId + "/" + sessionDate;

        ContentValues cv = new ContentValues();
        cv.put("groupid", groupId);
        cv.put("siteid", targetProjectId);
        cv.put("foldername", folderRel);
        cv.put("motherfolder", motherFolder);
        cv.put("sessiondate", sessionDate);
        cv.put("description", remarks);
        cv.put("timestamp", now);
        cv.put("created_at", now);
        cv.put("updated_at", now);

        long inserted = db.insert(GeoDbHelper.TABLE_GROUPS, null, cv);
        if (inserted != -1) return groupId;

        String fallback = findExactGroup(db, motherFolder, targetProjectId, sessionDate);
        if (!fallback.isEmpty()) return fallback;
        throw new IllegalStateException("Unable to create target group for moved photos.");
    }

    private String findExactGroup(SQLiteDatabase db,
                                  String motherFolder,
                                  String targetProjectId,
                                  String sessionDate) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT groupid FROM " + GeoDbHelper.TABLE_GROUPS + " " +
                            "WHERE motherfolder=? AND siteid=? AND sessiondate=? " +
                            "ORDER BY COALESCE(updated_at,timestamp,'') DESC LIMIT 1",
                    new String[]{motherFolder, targetProjectId, sessionDate}
            );
            return c.moveToFirst() && !c.isNull(0) ? clean(c.getString(0)) : "";
        } finally {
            if (c != null) c.close();
        }
    }

    private static String dateOnly(String timestamp) {
        String value = clean(timestamp);
        return value.length() >= 10 ? value.substring(0, 10) : "";
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class MoveInfo {
        String siteId;
        String timestamp;
        String monitoringType;
        String sessionDate;
        String motherFolder;
        String groupRemarks;
        int status;
    }
}
