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

    /**
     * Backward-compatible upsert-only entry point. Callers that cannot guarantee
     * a complete authoritative server list must not prune local rows.
     */
    public void saveProjectsFromApi(List<ApiProjectItem> items) {
        saveProjectsFromApi(items, false);
    }

    /**
     * Saves the server list and, only for an authoritative /capture-targets response,
     * removes local project rows that no longer exist on the server.
     *
     * Projects with pending/failed local captures are retained so deleting a project
     * on the server cannot orphan field work that still needs to upload. Already
     * synced photos may keep their site/image history; the stale project itself is
     * removed from tbl_projects so it disappears from project selectors.
     */
    public void saveProjectsFromApi(List<ApiProjectItem> items, boolean reconcileMissing) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
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
                    cv.put("project_type", normalizeProjectType(p.projectType));
                    cv.put("division_id", safeNull(p.divisionId));
                    cv.put("division_code", safeNull(p.divisionCode));
                    cv.put("division_name", safeNull(p.divisionName));
                    cv.put("project_implementors", safeNull(p.projectImplementors));
                    cv.put("project_description", safeNull(p.projectDescription));
                    cv.put("date_from", safeNull(p.dateFrom));
                    cv.put("date_to", safeNull(p.dateTo));
                    cv.put("timestamp", now);

                    db.insertWithOnConflict(
                            GeoDbHelper.TABLE_PROJECTS,
                            null,
                            cv,
                            SQLiteDatabase.CONFLICT_REPLACE
                    );
                }
            }

            if (reconcileMissing && items != null) {
                reconcileMissingProjects(db, items);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    private void reconcileMissingProjects(SQLiteDatabase db, List<ApiProjectItem> serverItems) {
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS tmp_server_project_ids (projectid TEXT PRIMARY KEY COLLATE NOCASE)");
        db.execSQL("DELETE FROM tmp_server_project_ids");

        for (ApiProjectItem item : serverItems) {
            if (item == null) continue;
            String id = normalize(item.projectId);
            if (id.isEmpty()) continue;
            ContentValues values = new ContentValues();
            values.put("projectid", id);
            db.insertWithOnConflict("tmp_server_project_ids", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }

        // status 1 = successfully synced. Any other status may still need local work,
        // so keep its project row even if an administrator deleted it on the server.
        String pendingDirectCapture =
                "EXISTS (SELECT 1 FROM tbl_imagemeta im " +
                "WHERE im.status <> 1 " +
                "AND trim(COALESCE(im.activity_project_id,'')) = trim(tbl_projects.projectid) COLLATE NOCASE)";

        String pendingInfraCapture =
                "EXISTS (SELECT 1 FROM tbl_site s " +
                "JOIN tbl_imagemeta im ON trim(COALESCE(im.siteid,'')) = trim(COALESCE(s.siteid,'')) COLLATE NOCASE " +
                "WHERE im.status <> 1 " +
                "AND trim(COALESCE(s.projectid,'')) = trim(tbl_projects.projectid) COLLATE NOCASE)";

        db.execSQL(
                "DELETE FROM tbl_projects " +
                "WHERE NOT EXISTS (SELECT 1 FROM tmp_server_project_ids srv " +
                "                  WHERE trim(srv.projectid) = trim(tbl_projects.projectid) COLLATE NOCASE) " +
                "AND NOT " + pendingDirectCapture + " " +
                "AND NOT " + pendingInfraCapture
        );

        db.execSQL("DROP TABLE IF EXISTS tmp_server_project_ids");
    }

    public List<ProjectListItem> getProjectList() {
        List<ProjectListItem> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT projectid, code, coda, beneficiary, location, cost, timestamp " +
                            "FROM tbl_projects " +
                            "ORDER BY CASE WHEN timestamp IS NULL OR trim(timestamp) = '' THEN 1 ELSE 0 END, " +
                            "timestamp DESC, beneficiary COLLATE NOCASE ASC, coda COLLATE NOCASE ASC, " +
                            "code COLLATE NOCASE ASC, projectid COLLATE NOCASE ASC",
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

    public boolean hasAnyProjects() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT 1 FROM tbl_projects LIMIT 1", null);
            return c.moveToFirst();
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public boolean existsProjectId(String projectId) {
        String value = normalize(projectId);
        if (value.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT projectid FROM tbl_projects WHERE trim(projectid) = trim(?) COLLATE NOCASE LIMIT 1",
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
            String leading = extractLeadingReference(input);
            if (!leading.isEmpty()) {
                c = db.rawQuery(
                        "SELECT projectid FROM tbl_projects " +
                                "WHERE trim(projectid) = trim(?) COLLATE NOCASE OR trim(code) = trim(?) COLLATE NOCASE " +
                                "ORDER BY CASE WHEN trim(code)=trim(?) COLLATE NOCASE THEN 0 ELSE 1 END LIMIT 1",
                        new String[]{leading, leading, leading}
                );
                if (c.moveToFirst()) return valueAt(c, 0);
                c.close(); c = null;
            }

            c = db.rawQuery(
                    "SELECT projectid FROM tbl_projects WHERE trim(code)=trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{input}
            );
            if (c.moveToFirst()) return valueAt(c, 0);
            c.close(); c = null;

            c = db.rawQuery(
                    "SELECT projectid FROM tbl_projects WHERE trim(projectid)=trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{input}
            );
            if (c.moveToFirst()) return valueAt(c, 0);
            c.close(); c = null;

            c = db.rawQuery(
                    "SELECT projectid FROM tbl_projects WHERE trim(coda)=trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{input}
            );
            if (c.moveToFirst()) return valueAt(c, 0);
            c.close(); c = null;

            c = db.rawQuery(
                    "SELECT projectid FROM tbl_projects " +
                            "WHERE code LIKE ? COLLATE NOCASE OR coda LIKE ? COLLATE NOCASE OR projectid LIKE ? COLLATE NOCASE " +
                            "ORDER BY CASE " +
                            "WHEN trim(code)=trim(?) COLLATE NOCASE THEN 0 " +
                            "WHEN trim(coda)=trim(?) COLLATE NOCASE THEN 1 " +
                            "WHEN trim(projectid)=trim(?) COLLATE NOCASE THEN 2 ELSE 3 END, " +
                            "code COLLATE NOCASE ASC, coda COLLATE NOCASE ASC LIMIT 1",
                    new String[]{"%" + input + "%", "%" + input + "%", "%" + input + "%", input, input, input}
            );
            return c.moveToFirst() ? valueAt(c, 0) : null;
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public List<String> getProjectSuggestions(String query, int limit) {
        return getProjectSuggestions(query, limit, null);
    }

    public List<String> getProjectSuggestions(String query, int limit, String requiredProjectType) {
        List<String> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            String q = normalize(query);
            String type = normalize(requiredProjectType).toUpperCase(Locale.US);
            boolean filterType = !type.isEmpty();
            String typeWhere = filterType ? " AND upper(trim(project_type)) = ? " : "";

            if (q.isEmpty()) {
                String sql = "SELECT projectid, code, coda FROM tbl_projects WHERE 1=1 " + typeWhere +
                        "ORDER BY CASE WHEN timestamp IS NULL OR trim(timestamp) = '' THEN 1 ELSE 0 END, " +
                        "timestamp DESC, code COLLATE NOCASE ASC, coda COLLATE NOCASE ASC LIMIT ?";
                List<String> args = new ArrayList<>();
                if (filterType) args.add(type);
                args.add(String.valueOf(limit));
                c = db.rawQuery(sql, args.toArray(new String[0]));
            } else {
                String sql = "SELECT projectid, code, coda FROM tbl_projects " +
                        "WHERE (code LIKE ? COLLATE NOCASE OR coda LIKE ? COLLATE NOCASE " +
                        "OR beneficiary LIKE ? COLLATE NOCASE OR projectid LIKE ? COLLATE NOCASE) " + typeWhere +
                        "ORDER BY CASE " +
                        "WHEN trim(code)=trim(?) COLLATE NOCASE THEN 0 " +
                        "WHEN trim(projectid)=trim(?) COLLATE NOCASE THEN 1 " +
                        "WHEN code LIKE ? COLLATE NOCASE THEN 2 " +
                        "WHEN projectid LIKE ? COLLATE NOCASE THEN 3 " +
                        "WHEN coda LIKE ? COLLATE NOCASE THEN 4 " +
                        "WHEN beneficiary LIKE ? COLLATE NOCASE THEN 5 ELSE 6 END, " +
                        "timestamp DESC, code COLLATE NOCASE ASC, coda COLLATE NOCASE ASC LIMIT ?";
                List<String> args = new ArrayList<>();
                String like = "%" + q + "%";
                String prefix = q + "%";
                args.add(like); args.add(like); args.add(like); args.add(like);
                if (filterType) args.add(type);
                args.add(q); args.add(q); args.add(prefix); args.add(prefix); args.add(prefix); args.add(prefix);
                args.add(String.valueOf(limit));
                c = db.rawQuery(sql, args.toArray(new String[0]));
            }

            while (c.moveToNext()) {
                String projectId = valueAt(c, 0);
                String code = valueAt(c, 1);
                String coda = valueAt(c, 2);
                String label;
                if (!code.isEmpty() && !coda.isEmpty()) label = code + " — " + coda;
                else if (!code.isEmpty()) label = code;
                else if (!coda.isEmpty()) label = coda;
                else label = projectId;
                if (!label.isEmpty() && !list.contains(label)) list.add(label);
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
                    "SELECT projectid, code, coda FROM tbl_projects WHERE trim(projectid)=trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{id}
            );
            if (!c.moveToFirst()) return null;
            String pid = valueAt(c, 0);
            String code = valueAt(c, 1);
            String coda = valueAt(c, 2);
            if (!code.isEmpty() && !coda.isEmpty()) return code + " — " + coda;
            if (!code.isEmpty()) return code;
            if (!coda.isEmpty()) return coda;
            return pid;
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public String getProjectCodeById(String projectId) {
        return getStringColumnByProjectId(projectId, "code");
    }

    public String getProjectTypeById(String projectId) {
        return normalizeProjectType(getStringColumnByProjectId(projectId, "project_type"));
    }

    public String getDivisionCodeByProjectId(String projectId) {
        return getStringColumnByProjectId(projectId, "division_code");
    }

    public String getProjectCodaById(String projectId) {
        return getStringColumnByProjectId(projectId, "coda");
    }

    public String getLatestProjectDisplay() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT coda, projectid, code FROM tbl_projects " +
                            "ORDER BY CASE WHEN timestamp IS NULL OR trim(timestamp) = '' THEN 1 ELSE 0 END, " +
                            "timestamp DESC, coda COLLATE NOCASE ASC LIMIT 1",
                    null
            );
            if (!c.moveToFirst()) return null;
            String coda = valueAt(c, 0);
            String projectId = valueAt(c, 1);
            String code = valueAt(c, 2);
            if (!coda.isEmpty()) return coda;
            if (!code.isEmpty()) return code;
            return projectId.isEmpty() ? null : projectId;
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    public String getProjectBeneficiaryById(String projectId) {
        return getStringColumnByProjectId(projectId, "beneficiary");
    }

    public String getProjectLocationById(String projectId) {
        return getStringColumnByProjectId(projectId, "location");
    }

    private String getStringColumnByProjectId(String projectId, String column) {
        String id = normalize(projectId);
        if (id.isEmpty()) return null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT " + column + " FROM tbl_projects WHERE trim(projectid)=trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{id}
            );
            if (!c.moveToFirst() || c.isNull(0)) return null;
            String value = normalize(c.getString(0));
            return value.isEmpty() ? null : value;
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    private String extractLeadingReference(String input) {
        String s = normalize(input);
        if (s.isEmpty()) return "";
        int idx = s.indexOf(" — ");
        if (idx < 0) idx = s.indexOf(" - ");
        if (idx > 0) return normalize(s.substring(0, idx));
        return s;
    }

    private String normalizeDate(String value) {
        if (value == null || value.trim().isEmpty()) return "-";
        return value.trim();
    }

    private static String normalizeProjectType(String value) {
        String type = normalize(value).toUpperCase(Locale.US);
        if ("PROJECT_ACTIVITY".equals(type)) return "PROJECT_ACTIVITY";
        if ("ACTIVITY".equals(type)) return "ACTIVITY";
        if ("PROJECT".equals(type)) return "PROJECT";
        if ("INFRA".equals(type) || "INFRASTRUCTURE".equals(type)) return "INFRA";
        return "INFRA";
    }

    private static String safeNull(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String s) {
        if (s == null) return "";
        s = s.trim().replace("\n", " ").replace("\r", " ");
        while (s.contains("  ")) s = s.replace("  ", " ");
        return s.trim();
    }

    private static String valueAt(Cursor c, int index) {
        return c == null || c.isNull(index) ? "" : normalize(c.getString(index));
    }
}
