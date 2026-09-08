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
                    "SELECT projectid FROM tbl_projects " +
                            "WHERE trim(projectid) = trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{value}
            );
            return c.moveToFirst();
        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    /** Resolve a GUID, project code, project title, or a displayed "CODE — Title" label. */
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
                                "WHERE trim(projectid) = trim(?) COLLATE NOCASE " +
                                "   OR trim(code) = trim(?) COLLATE NOCASE " +
                                "ORDER BY CASE WHEN trim(code)=trim(?) COLLATE NOCASE THEN 0 ELSE 1 END " +
                                "LIMIT 1",
                        new String[]{leading, leading, leading}
                );
                if (c.moveToFirst()) return valueAt(c, 0);
                c.close();
                c = null;
            }

            // Project Code is the primary human-facing identifier.
            c = db.rawQuery(
                    "SELECT projectid FROM tbl_projects " +
                            "WHERE trim(code) = trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{input}
            );
            if (c.moveToFirst()) return valueAt(c, 0);
            c.close();
            c = null;

            // Still accept GUIDs for old QR codes / existing workflows.
            c = db.rawQuery(
                    "SELECT projectid FROM tbl_projects " +
                            "WHERE trim(projectid) = trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{input}
            );
            if (c.moveToFirst()) return valueAt(c, 0);
            c.close();
            c = null;

            c = db.rawQuery(
                    "SELECT projectid FROM tbl_projects " +
                            "WHERE trim(coda) = trim(?) COLLATE NOCASE LIMIT 1",
                    new String[]{input}
            );
            if (c.moveToFirst()) return valueAt(c, 0);
            c.close();
            c = null;

            c = db.rawQuery(
                    "SELECT projectid FROM tbl_projects " +
                            "WHERE code LIKE ? COLLATE NOCASE " +
                            "   OR coda LIKE ? COLLATE NOCASE " +
                            "   OR projectid LIKE ? COLLATE NOCASE " +
                            "ORDER BY " +
                            "CASE " +
                            "  WHEN trim(code) = trim(?) COLLATE NOCASE THEN 0 " +
                            "  WHEN trim(coda) = trim(?) COLLATE NOCASE THEN 1 " +
                            "  WHEN trim(projectid) = trim(?) COLLATE NOCASE THEN 2 " +
                            "  ELSE 3 " +
                            "END, code COLLATE NOCASE ASC, coda COLLATE NOCASE ASC " +
                            "LIMIT 1",
                    new String[]{
                            "%" + input + "%",
                            "%" + input + "%",
                            "%" + input + "%",
                            input, input, input
                    }
            );

            if (c.moveToFirst()) return valueAt(c, 0);
            return null;

        } finally {
            if (c != null) c.close();
            db.close();
        }
    }

    /**
     * Human-facing suggestions intentionally lead with Project Code instead of
     * the internal GUID. Both tbl_project.code and tbl_project_activity.project_code
     * arrive in this same local code column.
     */
    public List<String> getProjectSuggestions(String query, int limit) {
        List<String> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            String q = normalize(query);

            if (q.isEmpty()) {
                c = db.rawQuery(
                        "SELECT projectid, code, coda FROM tbl_projects " +
                                "ORDER BY code COLLATE NOCASE ASC, coda COLLATE NOCASE ASC " +
                                "LIMIT ?",
                        new String[]{String.valueOf(limit)}
                );
            } else {
                c = db.rawQuery(
                        "SELECT projectid, code, coda FROM tbl_projects " +
                                "WHERE code LIKE ? COLLATE NOCASE " +
                                "   OR coda LIKE ? COLLATE NOCASE " +
                                "   OR projectid LIKE ? COLLATE NOCASE " +
                                "ORDER BY " +
                                "CASE " +
                                "  WHEN trim(code) = trim(?) COLLATE NOCASE THEN 0 " +
                                "  WHEN code LIKE ? COLLATE NOCASE THEN 1 " +
                                "  WHEN coda LIKE ? COLLATE NOCASE THEN 2 " +
                                "  ELSE 3 " +
                                "END, code COLLATE NOCASE ASC, coda COLLATE NOCASE ASC " +
                                "LIMIT ?",
                        new String[]{
                                "%" + q + "%",
                                "%" + q + "%",
                                "%" + q + "%",
                                q,
                                q + "%",
                                q + "%",
                                String.valueOf(limit)
                        }
                );
            }

            while (c.moveToNext()) {
                String projectId = valueAt(c, 0);
                String code = valueAt(c, 1);
                String coda = valueAt(c, 2);

                String label;
                if (!code.isEmpty() && !coda.isEmpty()) {
                    label = code + " — " + coda;
                } else if (!code.isEmpty()) {
                    label = code;
                } else if (!coda.isEmpty()) {
                    label = coda;
                } else {
                    label = projectId;
                }

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
                    "SELECT projectid, code, coda FROM tbl_projects " +
                            "WHERE trim(projectid) = trim(?) COLLATE NOCASE LIMIT 1",
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
        String type = getStringColumnByProjectId(projectId, "project_type");
        return normalizeProjectType(type);
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
                            "ORDER BY " +
                            "CASE WHEN timestamp IS NULL OR trim(timestamp) = '' THEN 1 ELSE 0 END, " +
                            "timestamp DESC, coda COLLATE NOCASE ASC LIMIT 1",
                    null
            );

            if (c.moveToFirst()) {
                String coda = valueAt(c, 0);
                String projectId = valueAt(c, 1);
                String code = valueAt(c, 2);

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
                    "SELECT " + column + " FROM tbl_projects " +
                            "WHERE trim(projectid)=trim(?) COLLATE NOCASE LIMIT 1",
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
        if (idx < 0) return "";
        return normalize(s.substring(0, idx));
    }

    private String normalizeDate(String value) {
        if (value == null || value.trim().isEmpty()) return "-";
        return value.trim();
    }

    private static String normalizeProjectType(String value) {
        String type = normalize(value).toUpperCase(Locale.US);
        if ("PROJECT_ACTIVITY".equals(type)) return "PROJECT_ACTIVITY";
        return "INFRA";
    }

    private static String valueAt(Cursor c, int index) {
        if (c == null || c.isNull(index)) return "";
        return normalize(c.getString(index));
    }

    private static String normalize(String s) {
        if (s == null) return "";
        s = s.trim();
        s = s.replace("\n", " ").replace("\r", " ").trim();
        while (s.contains("  ")) s = s.replace("  ", " ");
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
            db.close();
        }
    }

    private static String safeNull(String s) {
        String v = normalize(s);
        return v.isEmpty() ? null : v;
    }
}
