package ph.gov.geocamera.data.seed;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ph.gov.geocamera.data.remote.ApiProjectItem;
import ph.gov.geocamera.data.repository.ProjectRepository;

public final class ProjectSeedImporter {

    private static final String TAG = "PROJECT_SEED";
    private static final String PREFS = "project_seed_prefs";
    private static final String KEY_LAST_SEED_VERSION = "last_seed_version";
    private static final String ASSET_FILE = "projects_seed.json";

    private ProjectSeedImporter() {}

    public static void importOrUpdate(Context context) {
        try {
            Context app = context.getApplicationContext();

            String json = readAsset(app, ASSET_FILE);
            if (json.trim().isEmpty()) {
                Log.d(TAG, "Seed JSON is empty.");
                return;
            }

            JSONObject root = new JSONObject(json);
            int seedVersion = root.optInt("seed_version", 0);
            JSONArray arr = root.optJSONArray("items");

            if (seedVersion <= 0 || arr == null || arr.length() == 0) {
                Log.d(TAG, "Invalid seed file.");
                return;
            }

            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int lastVersion = prefs.getInt(KEY_LAST_SEED_VERSION, 0);

            if (seedVersion <= lastVersion) {
                Log.d(TAG, "Seed already imported. seedVersion=" + seedVersion + ", lastVersion=" + lastVersion);
                return;
            }

            List<ApiProjectItem> items = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);

                ApiProjectItem item = new ApiProjectItem();

                // JSON key is project_id, Java model field is projectId.
                item.projectId = safe(o.optString("project_id", ""));
                item.code = safe(o.optString("code", ""));
                item.name = safe(o.optString("name", ""));
                item.cost = o.optDouble("cost", 0);
                item.beneficiary = safe(o.optString("beneficiary", ""));
                item.location = safe(o.optString("location", ""));

                if (!item.projectId.isEmpty() || !item.code.isEmpty()) {
                    items.add(item);
                }
            }

            if (items.isEmpty()) {
                Log.d(TAG, "No valid seed project rows.");
                return;
            }

            ProjectRepository repo = new ProjectRepository(app);
            repo.saveProjectsFromApi(items);

            prefs.edit()
                    .putInt(KEY_LAST_SEED_VERSION, seedVersion)
                    .apply();

            Log.d(TAG, "Seed imported/updated. version=" + seedVersion + ", count=" + items.size());

        } catch (Exception e) {
            Log.e(TAG, "Seed import failed", e);
        }
    }

    public static void resetSeedVersion(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_SEED_VERSION)
                .apply();
    }

    private static String readAsset(Context context, String filename) throws Exception {
        InputStream is = context.getAssets().open(filename);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        byte[] buffer = new byte[4096];
        int read;

        while ((read = is.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }

        is.close();

        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
