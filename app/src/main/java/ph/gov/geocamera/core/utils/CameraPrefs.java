package ph.gov.geocamera.core.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class CameraPrefs {

    private static final String PREF = "camera_prefs";

    // Existing camera selection keys. Keep these unchanged for compatibility.
    private static final String KEY_SITE_ID = "site_id";
    private static final String KEY_UNCATEGORIZED = "uncategorized";
    private static final String KEY_DESCRIPTION = "photo_description";
    private static final String KEY_INDOOR_ASSIST = "indoor_assist_enabled";

    // Capture classification persisted locally and stamped into each photo.
    private static final String KEY_DOCUMENTATION_TYPE = "documentation_type";
    private static final String KEY_ACTIVITY_PROJECT_ID = "activity_project_id";

    // Preserve the last Infrastructure selection when temporarily switching
    // the camera to Project Activity mode.
    private static final String KEY_INFRA_SITE_ID = "infra_site_id";
    private static final String KEY_INFRA_UNCATEGORIZED = "infra_uncategorized";
    private static final String KEY_INFRA_SELECTION_SAVED = "infra_selection_saved";

    // Internal placeholder used only to prevent the legacy Site picker from
    // appearing before the first capture-target selection.
    public static final String DOC_SELECTION_PLACEHOLDER = "__DOC_MODE_PENDING__";

    // Retained only for backward compatibility with the first local prototype.
    // The current UI no longer asks for a shot type.
    private static final String KEY_SHOT_TYPE = "shot_type";

    public static final String DOC_INFRA = "INFRA";
    public static final String DOC_PROJECT_ACTIVITY = "PROJECT_ACTIVITY";
    public static final String DOC_PERSONAL = "PERSONAL";
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

    public void clearSiteSelection() {
        sp.edit()
                .remove(KEY_SITE_ID)
                .remove(KEY_UNCATEGORIZED)
                .apply();
    }

    /**
     * Called during camera layout inflation on a brand-new configuration.
     * It suppresses the existing Site picker just long enough for the new
     * capture-target question to appear first.
     */
    public void primeDocumentationSelectionPlaceholder() {
        if (!hasDocumentationType() && !hasSelection()) {
            saveSite(DOC_SELECTION_PLACEHOLDER, false);
        }
    }

    public boolean isDocumentationPlaceholderSelection() {
        String siteId = getSiteId();
        return siteId != null && DOC_SELECTION_PLACEHOLDER.equals(siteId);
    }

    public void clearDocumentationPlaceholderIfPresent() {
        if (isDocumentationPlaceholderSelection()) clearSiteSelection();
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
        sp.edit().putBoolean(KEY_INDOOR_ASSIST, enabled).apply();
    }

    public boolean isIndoorAssistEnabled() {
        return sp.getBoolean(KEY_INDOOR_ASSIST, false);
    }

    public void saveDocumentationType(String type) {
        String value = normalizeDocumentationType(type);
        SharedPreferences.Editor editor = sp.edit();
        if (value == null) editor.remove(KEY_DOCUMENTATION_TYPE);
        else editor.putString(KEY_DOCUMENTATION_TYPE, value);
        editor.apply();
    }

    public boolean hasDocumentationType() {
        return normalizeDocumentationType(sp.getString(KEY_DOCUMENTATION_TYPE, null)) != null;
    }

    public String getDocumentationType() {
        return normalizeDocumentationType(sp.getString(KEY_DOCUMENTATION_TYPE, null));
    }

    public void saveActivityProjectId(String projectId) {
        String value = projectId == null ? "" : projectId.trim();
        SharedPreferences.Editor editor = sp.edit();
        if (value.isEmpty()) editor.remove(KEY_ACTIVITY_PROJECT_ID);
        else editor.putString(KEY_ACTIVITY_PROJECT_ID, value);
        editor.apply();
    }

    public String getActivityProjectId() {
        String value = sp.getString(KEY_ACTIVITY_PROJECT_ID, null);
        return value == null ? null : value.trim();
    }

    public boolean hasActivityProjectId() {
        String value = getActivityProjectId();
        return value != null && !value.isEmpty();
    }

    public void clearActivityProjectId() {
        sp.edit().remove(KEY_ACTIVITY_PROJECT_ID).apply();
    }

    /** Save the current legacy Project/Site selection before entering Activity mode. */
    public void rememberInfrastructureSelection() {
        if (!hasSelection() || isDocumentationPlaceholderSelection()) return;

        sp.edit()
                .putBoolean(KEY_INFRA_SELECTION_SAVED, true)
                .putString(KEY_INFRA_SITE_ID, getSiteId())
                .putBoolean(KEY_INFRA_UNCATEGORIZED, isUncategorized())
                .apply();
    }

    /** Restore the last Infrastructure Project/Site selection, if one was saved. */
    public boolean restoreInfrastructureSelection() {
        if (!sp.getBoolean(KEY_INFRA_SELECTION_SAVED, false)) {
            clearSiteSelection();
            return false;
        }

        boolean uncategorized = sp.getBoolean(KEY_INFRA_UNCATEGORIZED, false);
        String siteId = sp.getString(KEY_INFRA_SITE_ID, null);
        saveSite(siteId, uncategorized);
        return uncategorized || (siteId != null && !siteId.trim().isEmpty());
    }

    // ---------------------------------------------------------------------
    // Legacy shot-type helpers retained so an older branch/class can still
    // compile. Current workflow always treats it as GENERAL and does not ask.
    // ---------------------------------------------------------------------
    public void saveShotType(String shotType) {
        sp.edit().putString(KEY_SHOT_TYPE, SHOT_GENERAL).apply();
    }

    public String getShotType() {
        return SHOT_GENERAL;
    }

    public void clearDocumentationMode() {
        sp.edit()
                .remove(KEY_DOCUMENTATION_TYPE)
                .remove(KEY_ACTIVITY_PROJECT_ID)
                .remove(KEY_SHOT_TYPE)
                .apply();
    }

    private String normalizeDocumentationType(String type) {
        if (type != null && DOC_INFRA.equalsIgnoreCase(type.trim())) return DOC_INFRA;
        if (type != null && DOC_PROJECT_ACTIVITY.equalsIgnoreCase(type.trim())) return DOC_PROJECT_ACTIVITY;
        if (type != null && DOC_PERSONAL.equalsIgnoreCase(type.trim())) return DOC_PERSONAL;
        return null;
    }

    public void clear() {
        sp.edit().clear().apply();
    }
}
