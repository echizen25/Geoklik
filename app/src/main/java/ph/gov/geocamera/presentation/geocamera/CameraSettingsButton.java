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

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.presentation.site.SetSiteActivity;

/**
 * Wraps GeoCameraActivity's gear button with a mode-aware settings surface.
 * The old click listener is retained only as a fallback outside the camera.
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
        MaterialButton btnMode = content.findViewById(R.id.btnCameraDocMode);
        MaterialButton btnProject = content.findViewById(R.id.btnCameraProjectSelection);
        MaterialButton btnIndoor = content.findViewById(R.id.btnCameraIndoorAssist);

        String type = prefs.getDocumentationType();
        boolean activityMode = CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type);

        if (activityMode) {
            String projectId = prefs.getActivityProjectId();
            String display = getProjectDisplay(activity, projectId);
            tvMode.setText(display == null
                    ? "Project Activity"
                    : "Project Activity • " + display);
            btnProject.setText("Change activity project");
            btnProject.setIconResource(R.drawable.ic_project_activity_24);
        } else if (CameraPrefs.DOC_INFRA.equals(type)) {
            tvMode.setText("Infrastructure");
            btnProject.setText("Change Project / Site");
            btnProject.setIconResource(R.drawable.ic_infrastructure_24);
        } else {
            tvMode.setText("Choose documentation mode");
            btnProject.setVisibility(View.GONE);
        }

        boolean indoorEnabled = prefs.isIndoorAssistEnabled();
        btnIndoor.setText(indoorEnabled ? "Indoor Assist: ON" : "Indoor Assist: OFF");

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .setNegativeButton("Close", null)
                .create();

        btnMode.setOnClickListener(v -> {
            dialog.dismiss();
            modeChip.postDelayed(modeChip::showDocumentationSettings, 100);
        });

        btnProject.setOnClickListener(v -> {
            dialog.dismiss();
            if (activityMode) {
                modeChip.postDelayed(modeChip::showActivityProjectSettings, 100);
            } else {
                activity.startActivity(new Intent(activity, SetSiteActivity.class));
            }
        });

        btnIndoor.setOnClickListener(v -> {
            if (!prefs.isIndoorAssistEnabled()) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Enable Indoor Assist?")
                        .setMessage("Indoor Assist uses assisted/network location when GPS is weak. " +
                                "This can reduce location accuracy.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Enable", (d, w) -> {
                            prefs.saveIndoorAssistEnabled(true);
                            dialog.dismiss();
                            Toast.makeText(activity, "Indoor Assist ON", Toast.LENGTH_SHORT).show();
                            activity.recreate();
                        })
                        .show();
            } else {
                prefs.saveIndoorAssistEnabled(false);
                dialog.dismiss();
                Toast.makeText(activity, "Indoor Assist OFF (GPS-Only)", Toast.LENGTH_SHORT).show();
                activity.recreate();
            }
        });

        dialog.show();
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
