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

    // Local-only documentation metadata. These are intentionally not part of
    // the current API/upload contract yet.
    private static final String KEY_DOCUMENTATION_TYPE = "documentation_type";
    private static final String KEY_SHOT_TYPE = "shot_type";

    public static final String DOC_INFRA = "INFRA";
    public static final String DOC_PROJECT_ACTIVITY = "PROJECT_ACTIVITY";
    public static final String SHOT_GENERAL = "GENERAL";

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

    public void saveDocumentationType(String type) {
        String value = normalizeDocumentationType(type);
        sp.edit()
                .putString(KEY_DOCUMENTATION_TYPE, value)
                .putString(KEY_SHOT_TYPE, SHOT_GENERAL)
                .apply();
    }

    public boolean hasDocumentationType() {
        String type = sp.getString(KEY_DOCUMENTATION_TYPE, null);
        return DOC_INFRA.equals(type) || DOC_PROJECT_ACTIVITY.equals(type);
    }

    public String getDocumentationType() {
        String type = sp.getString(KEY_DOCUMENTATION_TYPE, null);
        return normalizeDocumentationType(type);
    }

    public void saveShotType(String shotType) {
        String value = shotType == null ? SHOT_GENERAL : shotType.trim().toUpperCase();
        if (value.isEmpty()) value = SHOT_GENERAL;
        sp.edit().putString(KEY_SHOT_TYPE, value).apply();
    }

    public String getShotType() {
        String value = sp.getString(KEY_SHOT_TYPE, SHOT_GENERAL);
        if (value == null || value.trim().isEmpty()) return SHOT_GENERAL;
        return value.trim().toUpperCase();
    }

    public void clearDocumentationMode() {
        sp.edit()
                .remove(KEY_DOCUMENTATION_TYPE)
                .remove(KEY_SHOT_TYPE)
                .apply();
    }

    private String normalizeDocumentationType(String type) {
        if (type != null && DOC_INFRA.equalsIgnoreCase(type.trim())) return DOC_INFRA;
        if (type != null && DOC_PROJECT_ACTIVITY.equalsIgnoreCase(type.trim())) return DOC_PROJECT_ACTIVITY;
        return DOC_PROJECT_ACTIVITY;
    }

    public void clear() {
        sp.edit().clear().apply();
    }
}
