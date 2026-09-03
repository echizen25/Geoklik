package ph.gov.geocamera.data.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class AppVersionService {

    private static final String VERSION_URL =
            "https://app.philmech.gov.ph/geomap_api/api/geocamera/app-version";

    private static final String PREFS = "geoklik_app_version_policy";
    private static final String KEY_LATEST_CODE = "latest_code";
    private static final String KEY_LATEST_NAME = "latest_name";
    private static final String KEY_MIN_CODE = "minimum_code";
    private static final String KEY_FORCE = "force_update";
    private static final String KEY_MESSAGE = "message";

    public interface Callback {
        void onResult(VersionPolicy policy, boolean fromServer);
    }

    public static class VersionPolicy {
        public final int latestVersionCode;
        public final String latestVersionName;
        public final int minimumVersionCode;
        public final boolean forceUpdate;
        public final String message;

        public VersionPolicy(
                int latestVersionCode,
                String latestVersionName,
                int minimumVersionCode,
                boolean forceUpdate,
                String message
        ) {
            this.latestVersionCode = latestVersionCode;
            this.latestVersionName = latestVersionName == null ? "" : latestVersionName.trim();
            this.minimumVersionCode = minimumVersionCode;
            this.forceUpdate = forceUpdate;
            this.message = message == null || message.trim().isEmpty()
                    ? "A new GeoKlik update is available."
                    : message.trim();
        }

        public boolean isUpdateAvailable(int installedVersionCode) {
            return latestVersionCode > installedVersionCode;
        }

        public boolean isUpdateRequired(int installedVersionCode) {
            return installedVersionCode < minimumVersionCode
                    || (forceUpdate && installedVersionCode < latestVersionCode);
        }
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AppVersionService(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void check(Callback callback) {
        new Thread(() -> {
            VersionPolicy policy;
            boolean fromServer = false;

            try {
                policy = fetchFromServer();
                saveCached(policy);
                fromServer = true;
            } catch (Exception ignored) {
                policy = loadCached();
            }

            VersionPolicy finalPolicy = policy;
            boolean finalFromServer = fromServer;
            mainHandler.post(() -> callback.onResult(finalPolicy, finalFromServer));
        }).start();
    }

    private VersionPolicy fetchFromServer() throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;

        try {
            URL url = new URL(VERSION_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }

            in = new BufferedInputStream(conn.getInputStream());
            JSONObject root = new JSONObject(readFully(in));

            return new VersionPolicy(
                    root.optInt("latestVersionCode", 0),
                    root.optString("latestVersionName", ""),
                    root.optInt("minimumVersionCode", 0),
                    root.optBoolean("forceUpdate", false),
                    root.optString("message", "A new GeoKlik update is available.")
            );
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private void saveCached(VersionPolicy policy) {
        if (policy == null) return;

        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_LATEST_CODE, policy.latestVersionCode)
                .putString(KEY_LATEST_NAME, policy.latestVersionName)
                .putInt(KEY_MIN_CODE, policy.minimumVersionCode)
                .putBoolean(KEY_FORCE, policy.forceUpdate)
                .putString(KEY_MESSAGE, policy.message)
                .apply();
    }

    private VersionPolicy loadCached() {
        SharedPreferences p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new VersionPolicy(
                p.getInt(KEY_LATEST_CODE, 0),
                p.getString(KEY_LATEST_NAME, ""),
                p.getInt(KEY_MIN_CODE, 0),
                p.getBoolean(KEY_FORCE, false),
                p.getString(KEY_MESSAGE, "A new GeoKlik update is available.")
        );
    }

    private String readFully(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;

        while ((read = in.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }

        return bos.toString("UTF-8");
    }
}
