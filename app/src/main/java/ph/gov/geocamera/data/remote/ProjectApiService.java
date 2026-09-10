package ph.gov.geocamera.data.remote;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ProjectApiService {

    private static final String TAG = "PROJECT_API";
    private static final String CAPTURE_TARGETS_URL =
            "https://geoklik.philmech.gov.ph/api/capture-targets";
    private static final String LEGACY_PROJECTS_URL =
            "https://geoklik.philmech.gov.ph/api/projects";

    /**
     * Fetch the unified capture target list. If the newly deployed endpoint is
     * temporarily unavailable, fall back to the original /projects endpoint so
     * existing Infrastructure selection remains usable.
     */
    public List<ApiProjectItem> fetchProjects() throws Exception {
        try {
            return fetchFromUrl(CAPTURE_TARGETS_URL, false);
        } catch (Exception primaryError) {
            Log.w(TAG, "capture-targets unavailable; falling back to /projects", primaryError);
            return fetchFromUrl(LEGACY_PROJECTS_URL, true);
        }
    }

    private List<ApiProjectItem> fetchFromUrl(String requestUrl, boolean legacyInfraOnly)
            throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;

        try {
            Log.d(TAG, "Requesting URL: " + requestUrl);

            URL url = new URL(requestUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "HTTP response code = " + responseCode);

            if (responseCode < 200 || responseCode >= 300) {
                throw new RuntimeException("HTTP " + responseCode + " from " + requestUrl);
            }

            in = new BufferedInputStream(conn.getInputStream());
            String json = readFully(in);

            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("items");

            List<ApiProjectItem> list = new ArrayList<>();
            if (arr == null) {
                Log.w(TAG, "items array is null");
                return list;
            }

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);

                ApiProjectItem item = new ApiProjectItem();

                // New capture-targets returns projectId. Legacy /projects also
                // keeps project_id, so accept either shape during rollout.
                item.projectId = firstNonBlank(
                        o.optString("projectId", ""),
                        o.optString("project_id", "")
                );
                item.code = clean(o.optString("code", ""));
                item.name = clean(o.optString("name", ""));
                item.beneficiary = nullableString(o, "beneficiary");
                item.location = nullableString(o, "location");
                item.cost = o.optDouble("cost", 0d);

                item.projectType = clean(o.optString("projectType", ""));
                if (item.projectType.isEmpty() && legacyInfraOnly) {
                    item.projectType = "INFRA";
                }
                if (item.projectType.isEmpty()) {
                    // Safe compatibility default for an older server response.
                    item.projectType = "INFRA";
                }

                item.divisionId = nullableString(o, "divisionId");
                item.divisionCode = nullableString(o, "divisionCode");
                item.divisionName = nullableString(o, "divisionName");
                item.projectImplementors = nullableString(o, "projectImplementors");
                item.projectDescription = nullableString(o, "projectDescription");
                item.dateFrom = nullableString(o, "dateFrom");
                item.dateTo = nullableString(o, "dateTo");

                // INFRA administrative-area metadata. It is intentionally absent
                // from the legacy /projects contract and null for Project Activity.
                item.adminAreaMetadataAvailable = !legacyInfraOnly
                        && (o.has("munCode") || o.has("brgyCode"));
                item.municipalityCode = nullableString(o, "munCode");
                item.barangayCode = nullableString(o, "brgyCode");

                // Older optional radius fields remain parseable for compatibility.
                item.geofenceMetadataAvailable = !legacyInfraOnly
                        && (o.has("geofenceLatitude")
                        || o.has("geofenceLongitude")
                        || o.has("geofenceRadiusMeters"));
                item.geofenceLatitude = nullableDouble(o, "geofenceLatitude");
                item.geofenceLongitude = nullableDouble(o, "geofenceLongitude");
                item.geofenceRadiusMeters = nullableDouble(o, "geofenceRadiusMeters");

                if (item.projectId.isEmpty()) continue;
                list.add(item);
            }

            Log.d(TAG, "fetchProjects() returning " + list.size() + " item(s) from " + requestUrl);
            return list;

        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private String nullableString(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String value = clean(o.optString(key, ""));
        return value.isEmpty() ? null : value;
    }

    private Double nullableDouble(JSONObject o, String key) {
        if (o == null || o.isNull(key) || !o.has(key)) return null;
        try {
            double value = o.getDouble(key);
            return Double.isFinite(value) ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        String a = clean(first);
        if (!a.isEmpty()) return a;
        return clean(second);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String readFully(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;

        while ((read = in.read(buf)) != -1) {
            bos.write(buf, 0, read);
        }

        return bos.toString("UTF-8");
    }
}
