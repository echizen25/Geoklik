package ph.gov.geocamera.presentation.geocamera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageButton;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.presentation.site.SetSiteActivity;

/**
 * Responsive camera settings surface.
 *
 * Documentation mode and Indoor Assist are direct switches to avoid nested
 * dialogs and unnecessary taps. Infrastructure keeps the existing Project/Site
 * workflow; Project Activity keeps its own local project selection.
 */
public class CameraSettingsButton extends AppCompatImageButton {

    private OnClickListener legacyListener;

    public CameraSettingsButton(@NonNull Context context) {
        super(context);
        init();
    }

    public CameraSettingsButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CameraSettingsButton(@NonNull Context context,
                                @Nullable AttributeSet attrs,
                                int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        super.setOnClickListener(v -> showModeAwareSettings());
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        // GeoCameraActivity still assigns its older settings listener. Retain it
        // only as a fallback while keeping this simplified settings panel active.
        legacyListener = l;
    }

    private void showModeAwareSettings() {
        Activity activity = findActivity(getContext());
        if (activity == null) {
            if (legacyListener != null) legacyListener.onClick(this);
            return;
        }

        CameraPrefs prefs = new CameraPrefs(activity);
        DocumentationModeChip modeChip = activity.findViewById(R.id.documentationModeChip);

        if (modeChip == null) {
            if (legacyListener != null) legacyListener.onClick(this);
            return;
        }

        View content = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_camera_settings, null, false);

        TextView tvMode = content.findViewById(R.id.tvCameraSettingsMode);
        TextView tvSelection = content.findViewById(R.id.tvCameraSettingsSelection);
        MaterialButton btnProject = content.findViewById(R.id.btnCameraProjectSelection);
        SwitchMaterial switchMode = content.findViewById(R.id.switchDocumentationMode);
        SwitchMaterial switchIndoor = content.findViewById(R.id.switchIndoorAssist);

        String type = prefs.getDocumentationType();
        boolean activityMode = CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type);
        boolean infraMode = CameraPrefs.DOC_INFRA.equals(type);

        bindDocumentationSummary(
                activity,
                prefs,
                tvMode,
                tvSelection,
                btnProject,
                activityMode,
                infraMode
        );

        // Set initial values before listeners so opening Settings never changes state.
        switchMode.setChecked(activityMode);
        switchIndoor.setChecked(prefs.isIndoorAssistEnabled());

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .setNegativeButton("Close", null)
                .create();

        switchMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean currentlyActivity = CameraPrefs.DOC_PROJECT_ACTIVITY.equals(
                    prefs.getDocumentationType());
            if (currentlyActivity == isChecked) return;

            dialog.dismiss();

            if (isChecked) {
                switchToProjectActivity(activity, prefs, modeChip);
            } else {
                switchToInfrastructure(activity, prefs);
            }
        });

        btnProject.setOnClickListener(v -> {
            dialog.dismiss();

            if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(prefs.getDocumentationType())) {
                modeChip.postDelayed(modeChip::showActivityProjectSettings, 80);
            } else {
                // Existing SetSiteActivity writes to CameraPrefs. GeoCameraActivity
                // reloads the selected Project/Site in onResume.
                activity.startActivity(new Intent(activity, SetSiteActivity.class));
            }
        });

        switchIndoor.setOnCheckedChangeListener((buttonView, enabled) -> {
            if (prefs.isIndoorAssistEnabled() == enabled) return;

            prefs.saveIndoorAssistEnabled(enabled);
            dialog.dismiss();

            Toast.makeText(
                    activity,
                    enabled ? "Indoor Assist ON" : "Indoor Assist OFF (GPS-Only)",
                    Toast.LENGTH_SHORT
            ).show();

            activity.recreate();
        });

        dialog.show();
    }

    private void bindDocumentationSummary(Activity activity,
                                          CameraPrefs prefs,
                                          TextView tvMode,
                                          TextView tvSelection,
                                          MaterialButton btnProject,
                                          boolean activityMode,
                                          boolean infraMode) {
        if (activityMode) {
            String projectId = prefs.getActivityProjectId();
            String display = getProjectDisplay(activity, projectId);

            tvMode.setText("Project Activity");
            tvSelection.setText(display == null
                    ? "Activity Project • Not selected"
                    : "Activity Project • " + display);

            btnProject.setVisibility(View.VISIBLE);
            btnProject.setText(display == null
                    ? "Select Activity Project"
                    : "Change Activity Project");
            btnProject.setIconResource(R.drawable.ic_project_activity_24);
            return;
        }

        if (infraMode) {
            tvMode.setText("Infrastructure");

            String selection;
            if (prefs.isUncategorized()) {
                selection = "My Photos";
            } else {
                String siteId = prefs.getSiteId();
                if (siteId == null
                        || siteId.trim().isEmpty()
                        || CameraPrefs.DOC_SELECTION_PLACEHOLDER.equals(siteId.trim())) {
                    selection = null;
                } else {
                    selection = getProjectDisplay(activity, siteId);
                    if (selection == null || selection.trim().isEmpty()) selection = siteId.trim();
                }
            }

            tvSelection.setText(selection == null
                    ? "Project / Site • Not selected"
                    : "Project / Site • " + selection);

            btnProject.setVisibility(View.VISIBLE);
            btnProject.setText(selection == null
                    ? "Select Project / Site"
                    : "Change Project / Site");
            btnProject.setIconResource(R.drawable.ic_infrastructure_24);
            return;
        }

        tvMode.setText("Choose documentation mode");
        tvSelection.setText("Select Infrastructure or Project Activity");
        btnProject.setVisibility(View.GONE);
    }

    private void switchToProjectActivity(Activity activity,
                                         CameraPrefs prefs,
                                         DocumentationModeChip modeChip) {
        String previous = prefs.getDocumentationType();

        prefs.clearDocumentationPlaceholderIfPresent();
        if (!CameraPrefs.DOC_PROJECT_ACTIVITY.equals(previous)) {
            prefs.rememberInfrastructureSelection();
        }

        prefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);

        if (prefs.hasActivityProjectId()) {
            String projectId = prefs.getActivityProjectId();
            prefs.saveSite(projectId, false);
            Toast.makeText(activity, "Project Activity mode", Toast.LENGTH_SHORT).show();
            activity.recreate();
            return;
        }

        // First Activity use: ask only for its Project ID, then return to camera.
        modeChip.postDelayed(modeChip::showActivityProjectSettings, 100);
    }

    private void switchToInfrastructure(Activity activity, CameraPrefs prefs) {
        prefs.clearDocumentationPlaceholderIfPresent();
        prefs.restoreInfrastructureSelection();
        prefs.saveDocumentationType(CameraPrefs.DOC_INFRA);

        Toast.makeText(activity, "Infrastructure mode", Toast.LENGTH_SHORT).show();

        // If there is no saved Infrastructure Project/Site, GeoCameraActivity's
        // existing selection guard will open SetSiteActivity after recreation.
        activity.recreate();
    }

    private String getProjectDisplay(Context context, String projectId) {
        if (projectId == null || projectId.trim().isEmpty()) return null;
        try {
            String full = new ProjectRepository(context).getProjectDisplayLabel(projectId.trim());
            if (full == null || full.trim().isEmpty()) return projectId.trim();

            String value = full.trim();
            int sep = value.indexOf(" — ");
            if (sep >= 0 && sep + 3 < value.length()) {
                String name = value.substring(sep + 3).trim();
                if (!name.isEmpty()) return name;
            }
            return value;
        } catch (Exception ignored) {
            return projectId.trim();
        }
    }

    private Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return null;
    }
}
