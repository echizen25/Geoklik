package ph.gov.geocamera.presentation.geocamera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageButton;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.presentation.site.SetSiteActivity;

/**
 * Responsive camera settings surface.
 *
 * Documentation mode is shown as two direct choices rather than a binary
 * switch. Infrastructure keeps GeoKlik's existing Project/Site workflow while
 * Project Activity keeps its own local project selection.
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
        // only as a fallback while this simplified settings panel is available.
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

        MaterialCardView optionInfra = content.findViewById(R.id.optionInfrastructureSettings);
        MaterialCardView optionActivity = content.findViewById(R.id.optionProjectActivitySettings);
        ImageView checkInfra = content.findViewById(R.id.checkInfrastructureSettings);
        ImageView checkActivity = content.findViewById(R.id.checkProjectActivitySettings);

        View projectCard = content.findViewById(R.id.cardCameraProjectSelection);
        TextView tvProjectLabel = content.findViewById(R.id.tvCameraSettingsProjectLabel);
        TextView tvSelection = content.findViewById(R.id.tvCameraSettingsSelection);
        TextView tvProjectId = content.findViewById(R.id.tvCameraSettingsProjectId);
        MaterialButton btnProject = content.findViewById(R.id.btnCameraProjectSelection);
        SwitchMaterial switchIndoor = content.findViewById(R.id.switchIndoorAssist);

        String type = prefs.getDocumentationType();
        boolean activityMode = CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type);
        boolean infraMode = CameraPrefs.DOC_INFRA.equals(type);

        bindModeChoice(optionInfra, checkInfra, infraMode);
        bindModeChoice(optionActivity, checkActivity, activityMode);
        bindProjectSummary(
                activity,
                prefs,
                projectCard,
                tvProjectLabel,
                tvSelection,
                tvProjectId,
                btnProject,
                activityMode,
                infraMode
        );

        // Opening Settings must never change saved state.
        switchIndoor.setChecked(prefs.isIndoorAssistEnabled());

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .setNegativeButton("Close", null)
                .create();

        optionInfra.setOnClickListener(v -> {
            if (CameraPrefs.DOC_INFRA.equals(prefs.getDocumentationType())) return;
            dialog.dismiss();
            switchToInfrastructure(activity, prefs);
        });

        optionActivity.setOnClickListener(v -> {
            if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(prefs.getDocumentationType())) return;
            dialog.dismiss();
            switchToProjectActivity(activity, prefs, modeChip);
        });

        btnProject.setOnClickListener(v -> {
            dialog.dismiss();

            if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(prefs.getDocumentationType())) {
                modeChip.postDelayed(modeChip::showActivityProjectSettings, 80);
            } else {
                // Existing SetSiteActivity writes to CameraPrefs. GeoCameraActivity
                // reloads the selected Infrastructure Project/Site in onResume.
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

        dialog.setOnShowListener(d -> configureResponsiveDialog(dialog));
        dialog.show();
    }

    private void bindModeChoice(MaterialCardView card,
                                ImageView check,
                                boolean selected) {
        check.setVisibility(selected ? View.VISIBLE : View.GONE);
        card.setStrokeWidth(dp(selected ? 2 : 1));
        card.setContentDescription(selected ? "Selected" : "Not selected");
    }

    private void bindProjectSummary(Activity activity,
                                    CameraPrefs prefs,
                                    View projectCard,
                                    TextView tvProjectLabel,
                                    TextView tvSelection,
                                    TextView tvProjectId,
                                    MaterialButton btnProject,
                                    boolean activityMode,
                                    boolean infraMode) {
        if (activityMode) {
            String projectId = clean(prefs.getActivityProjectId());
            String display = getProjectDisplay(activity, projectId);

            projectCard.setVisibility(View.VISIBLE);
            tvProjectLabel.setText("ACTIVITY PROJECT");
            tvSelection.setText(display == null
                    ? "No activity project selected"
                    : display);

            if (projectId == null) {
                tvProjectId.setVisibility(View.GONE);
            } else {
                tvProjectId.setText("Project ID  •  " + projectId);
                tvProjectId.setVisibility(View.VISIBLE);
            }

            btnProject.setText(projectId == null
                    ? "Choose Activity Project"
                    : "Change Activity Project");
            btnProject.setIconResource(R.drawable.ic_project_activity_24);
            return;
        }

        if (infraMode) {
            projectCard.setVisibility(View.VISIBLE);
            tvProjectLabel.setText("PROJECT / SITE");

            String siteId = null;
            String display = null;

            if (prefs.isUncategorized()) {
                display = "My Photos";
            } else {
                siteId = clean(prefs.getSiteId());
                if (CameraPrefs.DOC_SELECTION_PLACEHOLDER.equals(siteId)) siteId = null;
                if (siteId != null) {
                    display = getProjectDisplay(activity, siteId);
                    if (display == null || display.trim().isEmpty()) display = siteId;
                }
            }

            tvSelection.setText(display == null
                    ? "No project or site selected"
                    : display);

            if (siteId == null) {
                tvProjectId.setVisibility(View.GONE);
            } else {
                tvProjectId.setText("Project / Site ID  •  " + siteId);
                tvProjectId.setVisibility(View.VISIBLE);
            }

            btnProject.setText(siteId == null && !prefs.isUncategorized()
                    ? "Choose Project / Site"
                    : "Change Project / Site");
            btnProject.setIconResource(R.drawable.ic_infrastructure_24);
            return;
        }

        projectCard.setVisibility(View.GONE);
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
            Toast.makeText(activity, "Project Activity selected", Toast.LENGTH_SHORT).show();
            activity.recreate();
            return;
        }

        // First Activity use: continue directly to project selection.
        modeChip.postDelayed(modeChip::showActivityProjectSettings, 100);
    }

    private void switchToInfrastructure(Activity activity, CameraPrefs prefs) {
        prefs.clearDocumentationPlaceholderIfPresent();
        prefs.restoreInfrastructureSelection();
        prefs.saveDocumentationType(CameraPrefs.DOC_INFRA);

        Toast.makeText(activity, "Infrastructure selected", Toast.LENGTH_SHORT).show();

        // If no saved Infrastructure Project/Site exists, the camera's existing
        // selection guard will open SetSiteActivity after recreation.
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

    private String clean(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private void configureResponsiveDialog(AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(Math.max(1, screenWidth - dp(20)), dp(560));
        int maxHeight = Math.max(1, screenHeight - dp(16));

        dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        dialog.getWindow().getDecorView().post(() -> {
            if (!dialog.isShowing() || dialog.getWindow() == null) return;
            int measuredHeight = dialog.getWindow().getDecorView().getHeight();
            dialog.getWindow().setLayout(
                    width,
                    measuredHeight > maxHeight ? maxHeight : WindowManager.LayoutParams.WRAP_CONTENT
            );
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
