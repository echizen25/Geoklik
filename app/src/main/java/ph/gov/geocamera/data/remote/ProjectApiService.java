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
    private static final String PROJECTS_URL = "https://app.philmech.gov.ph/geomap_api/api/projects";

    public List<ApiProjectItem> fetchProjects() throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;

        try {
            Log.d(TAG, "Requesting URL: " + PROJECTS_URL);

            URL url = new URL(PROJECTS_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            Log.d(TAG, "HTTP response code = " + code);

            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code);
            }

            in = new BufferedInputStream(conn.getInputStream());
            String json = readFully(in);

            Log.d(TAG, "Raw JSON response = " + json);

            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("items");

            List<ApiProjectItem> list = new ArrayList<>();
            if (arr == null) {
                Log.w(TAG, "items array is null");
                return list;
            }

            Log.d(TAG, "items length = " + arr.length());

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);

                ApiProjectItem item = new ApiProjectItem();
                item.projectId = o.optString("project_id", "").trim();
                item.code = o.optString("code", "").trim();
                item.name = o.optString("name", "").trim();
                item.beneficiary = o.optString("beneficiary", "").trim();
                item.location = o.optString("location", "").trim();
                item.cost = o.optDouble("cost", 0d);

                list.add(item);

                Log.d(TAG, "PARSED ITEM => projectId=" + item.projectId
                        + ", code=" + item.code
                        + ", name=" + item.name
                        + ", beneficiary=" + item.beneficiary
                        + ", location=" + item.location
                        + ", cost=" + item.cost);
            }

            Log.d(TAG, "fetchProjects() returning " + list.size() + " item(s)");
            return list;

        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
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