package ph.gov.geocamera.core.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class CameraPrefs {

    private static final String PREF = "camera_prefs";

    // compatibility:
    // existing code still uses getSiteId()/saveSite(...)
    // sa current flow, KEY_SITE_ID stores the selected project/site code used by upload
    private static final String KEY_SITE_ID = "site_id";
    private static final String KEY_UNCATEGORIZED = "uncategorized";
    private static final String KEY_DESCRIPTION = "photo_description";
    private static final String KEY_INDOOR_ASSIST = "indoor_assist_enabled";

    private final SharedPreferences sp;

    public CameraPrefs(Context ctx) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public void saveSite(String siteId, boolean uncategorized) {
        sp.edit()
                .putString(KEY_SITE_ID, siteId == null ? null : siteId.trim())
                .putBoolean(KEY_UNCATEGORIZED, uncategorized)
                .apply();
    }

    public void saveDescription(String description) {
        sp.edit()
                .putString(KEY_DESCRIPTION, description == null ? "" : description.trim())
                .apply();
    }

    public boolean hasSelection() {
        if (!sp.contains(KEY_UNCATEGORIZED)) return false;

        boolean uncategorized = sp.getBoolean(KEY_UNCATEGORIZED, false);
        if (uncategorized) return true;

        String siteId = sp.getString(KEY_SITE_ID, null);
        return siteId != null && !siteId.trim().isEmpty();
    }

    public String getSiteId() {
        String s = sp.getString(KEY_SITE_ID, null);
        return s == null ? null : s.trim();
    }

    public boolean isUncategorized() {
        return sp.getBoolean(KEY_UNCATEGORIZED, false);
    }

    public String getDescription() {
        return sp.getString(KEY_DESCRIPTION, "");
    }

    public boolean hasDescription() {
        return sp.contains(KEY_DESCRIPTION)
                && getDescription() != null
                && !getDescription().trim().isEmpty();
    }

    public void clearDescription() {
        sp.edit().remove(KEY_DESCRIPTION).apply();
    }

    public void saveIndoorAssistEnabled(boolean enabled) {
        sp.edit()
                .putBoolean(KEY_INDOOR_ASSIST, enabled)
                .apply();
    }

    public boolean isIndoorAssistEnabled() {
        return sp.getBoolean(KEY_INDOOR_ASSIST, false);
    }

    public void clear() {
        sp.edit().clear().apply();
    }
}
