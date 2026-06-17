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
import ph.gov.geocamera.data.remote.ApiProjectItem;
import ph.gov.geocamera.presentation.library.ProjectListItem;

public class ProjectRepository {

    private final GeoDbHelper dbHelper;

    public ProjectRepository(Context context) {
        dbHelper = new GeoDbHelper(context);
    }

    public void saveProjectsFromApi(List<ApiProjectItem> items) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date());

            if (items != null) {
                for (ApiProjectItem p : items) {
                    if (p == null) continue;

                    String projectId = normalize(p.projectId);
                    if (projectId.isEmpty()) continue;

                    ContentValues cv = new ContentValues();
                    cv.put("projectid", projectId);
                    cv.put("code", safeNull(p.code));
                    cv.put("coda", safeNull(p.name));
                    cv.put("beneficiary", safeNull(p.beneficiary));
                    cv.put("location", safeNull(p.location));
                    cv.put("cost", p.cost);
                    cv.put("timestamp", now);

                    db.insertWithOnConflict(
                            GeoDbHelper.TABLE_PROJECTS,
                            null,
                            cv,
                            SQLiteDatabase.CONFLICT_REPLACE
                    );
                }
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    public List<ProjectListItem> getProjectList() {
        List<ProjectListItem> list = new ArrayList<>();

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT " +
                            "projectid, " +
                            "code, " +
                            "coda, " +
                            "beneficiary, " +
                            "location, " +
                            "cost, " +
                            "timestamp " +
                            "FROM tbl_projects " +
                            "ORDER BY " +
                            "CASE WHEN timestamp IS NULL OR trim(timestamp) = '' THEN 1 ELSE 0 END, " +
                            "timestamp DESC, " +
                            "beneficiary COLLATE NOCASE ASC, " +
                            "coda COLLATE NOCASE ASC, " +
                            "code COLLATE NOCASE ASC, " +
                            "projectid COLLATE NOCASE ASC",
                    null
            );

            int idxProjectId = c.getColumnIndexOrThrow("projectid");
            int idxCode = c.getColumnIndexOrThrow("code");
            int idxCoda = c.getColumnIndexOrThrow("coda");
            int idxBeneficiary = c.getColumnIndexOrThrow("beneficiary");
            int idxLocation = c.getColumnIndexOrThrow("location");
            int idxCost = c.getColumnIndexOrThrow("cost");
            int idxTimestamp = c.getColumnIndexOrThrow("timestamp");

            while (c.moveToNext()) {
                ProjectListItem item = new ProjectListItem();

                item.projectId = c.isNull(idxProjectId) ? null : c.getString(idxProjectId);
                item.code = c.isNull(idxCode) ? null : c.getString(idxCode);
                item.projectName = c.isNull(idxCoda) ? null : c.getString(idxCoda);
                item.beneficiary = c.isNull(idxBeneficiary) ? null : c.getString(idxBeneficiary);
                item.location = c.isNull(idxLocation) ? null : c.getString(idxLocation);

                double cost = c.isNull(idxCost) ? 0d : c.getDouble(idxCost);
                item.cost = String.format(Locale.US, "₱ %,.2f", cost);

                String ts = c.isNull(idxTimestamp) ? null : c.getString(idxTimestamp);
                item.dateAdded = normalizeDate(ts);
                item.dateModified = normalizeDate(ts);

                list.add(item);
            }
        } finally {
            if (c != null) c.close();
            db.close();
        }

        return list;
    }

    public boolean existsProjectId(String projectId) {
        String value = normalize(projectId);
        if (value.isEmpty()) return false;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT projectid " +
                            "FROM tbl_projects " +
                            "WHERE trim(projectid) = trim(?) COLLATE NOCASE " +
                            "LIMIT 1",
                    new String[]{value}
            );
            return c.moveToFirst();
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public String resolveProjectId(String rawInput) {
        String input = normalize(rawInput);
        if (input.isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            // 0) exact projectid
            c = db.rawQuery(
                    "SELECT projectid " +
                            "FROM tbl_projects " +
                            "WHERE trim(projectid) = trim(?) COLLATE NOCASE " +
                            "LIMIT 1",
                    new String[]{input}
            );
            if (c.moveToFirst()) {
                return c.isNull(0) ? null : normalize(c.getString(0));
            }
            c.close();
            c = null;

            // 1) exact coda
            c = db.rawQuery(
                    "SELECT projectid " +
                            "FROM tbl_projects " +
                            "WHERE trim(coda) = trim(?) COLLATE NOCASE " +
                            "LIMIT 1",
                    new String[]{input}
            );
            if (c.moveToFirst()) {
                return c.isNull(0) ? null : normalize(c.getString(0));
            }
            c.close();
            c = null;

            // 2) exact code
            c = db.rawQuery(
                    "SELECT projectid " +
                            "FROM tbl_projects " +
                            "WHERE trim(code) = trim(?) COLLATE NOCASE " +
                            "LIMIT 1",
                    new String[]{input}
            );
            if (c.moveToFirst()) {
                return c.isNull(0) ? null : normalize(c.getString(0));
            }
            c.close();
            c = null;

            // 3) label format: "projectid — coda" or "projectid - coda"
            String extracted = extractLeadingProjectId(input);
            if (!extracted.isEmpty()) {
                c = db.rawQuery(
                        "SELECT projectid " +
                                "FROM tbl_projects " +
                                "WHERE trim(projectid) = trim(?) COLLATE NOCASE " +
                                "LIMIT 1",
                        new String[]{extracted}
                );
                if (c.moveToFirst()) {
                    return c.isNull(0) ? null : normalize(c.getString(0));
                }
                c.close();
                c = null;
            }

            // 4) partial fallback
            c = db.rawQuery(
                    "SELECT projectid " +
                            "FROM tbl_projects " +
                            "WHERE projectid LIKE ? COLLATE NOCASE " +
                            "   OR code LIKE ? COLLATE NOCASE " +
                            "   OR coda LIKE ? COLLATE NOCASE " +
                            "ORDER BY " +
                            "CASE " +
                            "  WHEN trim(projectid) = trim(?) COLLATE NOCASE THEN 0 " +
                            "  WHEN trim(code) = trim(?) COLLATE NOCASE THEN 1 " +
                            "  WHEN trim(coda) = trim(?) COLLATE NOCASE THEN 2 " +
                            "  ELSE 3 " +
                            "END, " +
                            "coda COLLATE NOCASE ASC, " +
                            "projectid COLLATE NOCASE ASC " +
                            "LIMIT 1",
                    new String[]{
                            "%" + input + "%",
                            "%" + input + "%",
                            "%" + input + "%",
                            input,
                            input,
                            input
                    }
            );

            if (c.moveToFirst()) {
                return c.isNull(0) ? null : normalize(c.getString(0));
            }

            return null;

        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public List<String> getProjectSuggestions(String query, int limit) {
        List<String> list = new ArrayList<>();

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            String q = normalize(query);

            if (q.isEmpty()) {
                c = db.rawQuery(
                        "SELECT projectid, code, coda " +
                                "FROM tbl_projects " +
                                "ORDER BY coda COLLATE NOCASE ASC, code COLLATE NOCASE ASC, projectid COLLATE NOCASE ASC " +
                                "LIMIT ?",
                        new String[]{String.valueOf(limit)}
                );
            } else {
                c = db.rawQuery(
                        "SELECT projectid, code, coda " +
                                "FROM tbl_projects " +
                                "WHERE projectid LIKE ? COLLATE NOCASE " +
                                "   OR code LIKE ? COLLATE NOCASE " +
                                "   OR coda LIKE ? COLLATE NOCASE " +
                                "ORDER BY " +
                                "CASE " +
                                "  WHEN trim(projectid) = trim(?) COLLATE NOCASE THEN 0 " +
                                "  WHEN trim(code) = trim(?) COLLATE NOCASE THEN 1 " +
                                "  WHEN trim(coda) = trim(?) COLLATE NOCASE THEN 2 " +
                                "  ELSE 3 " +
                                "END, " +
                                "coda COLLATE NOCASE ASC, " +
                                "code COLLATE NOCASE ASC, " +
                                "projectid COLLATE NOCASE ASC " +
                                "LIMIT ?",
                        new String[]{
                                "%" + q + "%",
                                "%" + q + "%",
                                "%" + q + "%",
                                q,
                                q,
                                q,
                                String.valueOf(limit)
                        }
                );
            }

            while (c.moveToNext()) {
                String projectId = c.isNull(0) ? "" : normalize(c.getString(0));
                String code = c.isNull(1) ? "" : normalize(c.getString(1));
                String coda = c.isNull(2) ? "" : normalize(c.getString(2));

                String label;
                if (!coda.isEmpty()) {
                    label = coda;
                } else if (!code.isEmpty()) {
                    label = code;
                } else {
                    label = projectId;
                }

                if (!label.isEmpty() && !list.contains(label)) {
                    list.add(label);
                }

                if (!projectId.isEmpty()
                        && !list.contains(projectId)
                        && (q.isEmpty() || projectId.toLowerCase(Locale.US).contains(q.toLowerCase(Locale.US)))) {
                    list.add(projectId);
                }

                if (list.size() >= limit) break;
            }

        } finally {
            if (c != null) c.close();
            db.close();
        }

        return list;
    }

    public String getProjectDisplayLabel(String projectId) {
        String id = normalize(projectId);
        if (id.isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT projectid, code, coda " +
                            "FROM tbl_projects " +
                            "WHERE trim(projectid) = trim(?) COLLATE NOCASE " +
                            "LIMIT 1",
                    new String[]{id}
            );

            if (!c.moveToFirst()) return null;

            String pid = c.isNull(0) ? "" : normalize(c.getString(0));
            String code = c.isNull(1) ? "" : normalize(c.getString(1));
            String coda = c.isNull(2) ? "" : normalize(c.getString(2));

            if (!coda.isEmpty()) return pid + " — " + coda;
            if (!code.isEmpty()) return pid + " — " + code;
            return pid;
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public String getProjectCodaById(String projectId) {
        String id = normalize(projectId);
        if (id.isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT coda FROM tbl_projects WHERE trim(projectid) = trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{id}
            );

            if (c.moveToFirst()) {
                return c.isNull(0) ? null : normalize(c.getString(0));
            }
            return null;

        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public String getLatestProjectDisplay() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT coda, projectid, code " +
                            "FROM tbl_projects " +
                            "ORDER BY " +
                            "CASE WHEN timestamp IS NULL OR trim(timestamp) = '' THEN 1 ELSE 0 END, " +
                            "timestamp DESC, coda COLLATE NOCASE ASC " +
                            "LIMIT 1",
                    null
            );

            if (c.moveToFirst()) {
                String coda = c.isNull(0) ? "" : normalize(c.getString(0));
                String projectId = c.isNull(1) ? "" : normalize(c.getString(1));
                String code = c.isNull(2) ? "" : normalize(c.getString(2));

                if (!coda.isEmpty()) return coda;
                if (!code.isEmpty()) return code;
                if (!projectId.isEmpty()) return projectId;
            }

            return null;

        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public String getProjectBeneficiaryById(String projectId) {
        String id = normalize(projectId);
        if (id.isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT beneficiary FROM tbl_projects WHERE trim(projectid) = trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{id}
            );

            if (c.moveToFirst()) {
                return c.isNull(0) ? null : normalize(c.getString(0));
            }
            return null;

        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public String getProjectLocationById(String projectId) {
        String id = normalize(projectId);
        if (id.isEmpty()) return null;

        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            c = db.rawQuery(
                    "SELECT location FROM tbl_projects WHERE trim(projectid) = trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{id}
            );

            if (c.moveToFirst()) {
                return c.isNull(0) ? null : normalize(c.getString(0));
            }
            return null;

        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    private String extractLeadingProjectId(String input) {
        String s = normalize(input);
        if (s.isEmpty()) return "";

        int idx = s.indexOf(" — ");
        if (idx < 0) idx = s.indexOf(" - ");
        if (idx < 0) return "";

        return normalize(s.substring(0, idx));
    }

    private String normalizeDate(String value) {
        if (value == null || value.trim().isEmpty()) return "-";
        return value.trim();
    }

    private static String normalize(String s) {
        if (s == null) return "";
        s = s.trim();
        s = s.replace("\n", " ").replace("\r", " ").trim();
        while (s.contains("  ")) {
            s = s.replace("  ", " ");
        }
        return s;
    }

    public boolean hasAnyProjects() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT 1 FROM tbl_projects LIMIT 1", null);
            return c != null && c.moveToFirst();
        } finally {
            if (c != null) c.close();
        }
    }

    private static String safeNull(String s) {
        String v = normalize(s);
        return v.isEmpty() ? null : v;
    }
}