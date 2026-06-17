package ph.gov.geocamera.core.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class CameraPrefs {

    private static final String PREF = "camera_prefs";

    // compatibility:
    // existing code still uses getSiteId()/saveSite(...)
    // pero sa bagong flow, dito na natin sini-save ang selected PROJECT ID
    private static final String KEY_SITE_ID = "site_id";
    private static final String KEY_UNCATEGORIZED = "uncategorized";
    private static final String KEY_DESCRIPTION = "photo_description";

    private final SharedPreferences sp;

    public CameraPrefs(Context ctx) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // compatibility method name:
    // siteId param now stores selected PROJECT ID in the new flow
    public void saveSite(String siteId, boolean uncategorized) {
        sp.edit()
                .putString(KEY_SITE_ID, siteId)
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
        return sp.getString(KEY_SITE_ID, null) != null;
    }

    // compatibility getter:
    // returns selected PROJECT ID in the new flow
    public String getSiteId() {
        return sp.getString(KEY_SITE_ID, null);
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

    public void clear() {
        sp.edit().clear().apply();
    }
}