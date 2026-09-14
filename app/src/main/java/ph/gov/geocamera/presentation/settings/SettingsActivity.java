package ph.gov.geocamera.presentation.settings;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.presentation.common.BaseTopAppBarActivity;
import ph.gov.geocamera.presentation.geocamera.CameraFlashController;
import ph.gov.geocamera.presentation.geocamera.GeoCameraActivity;
import ph.gov.geocamera.presentation.home.HomeActivity;
import ph.gov.geocamera.presentation.site.SetSiteActivity;

public class SettingsActivity extends BaseTopAppBarActivity {

    private CameraPrefs cameraPrefs;
    private ProjectRepository projectRepo;

    private TextView tvCurrentProject;
    private TextView tvGpsModeValue;
    private TextView tvFlashValue;
    private TextView tvAppVersion;

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_settings;
    }

    @Override
    protected String getScreenTitle() {
        return "Settings";
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cameraPrefs = new CameraPrefs(this);
        projectRepo = new ProjectRepository(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
            toolbar.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_geocam) {
                    startActivity(new Intent(this, GeoCameraActivity.class));
                    return true;
                }
                if (id == R.id.action_home) {
                    Intent i = new Intent(this, HomeActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                    return true;
                }
                return false;
            });
        }

        tvCurrentProject = findViewById(R.id.tvCurrentProject);
        tvGpsModeValue = findViewById(R.id.tvGpsModeValue);
        tvFlashValue = findViewById(R.id.tvFlashValue);
        tvAppVersion = findViewById(R.id.tvAppVersion);

        View rowChangeProject = findViewById(R.id.rowChangeProject);
        View rowGpsMode = findViewById(R.id.rowGpsMode);
        View rowFlash = findViewById(R.id.rowFlash);
        View rowResetCamera = findViewById(R.id.rowResetCamera);

        if (rowChangeProject != null) {
            rowChangeProject.setOnClickListener(v ->
                    startActivity(new Intent(this, SetSiteActivity.class))
            );
        }

        if (rowGpsMode != null) {
            rowGpsMode.setOnClickListener(v -> showGpsModeDialog());
        }

        if (rowFlash != null) {
            rowFlash.setOnClickListener(v -> showFlashDialog());
        }

        if (rowResetCamera != null) {
            rowResetCamera.setOnClickListener(v -> confirmResetCameraSettings());
        }

        refreshValues();
        bindVersion();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
    }

    private void showGpsModeDialog() {
        String[] options = new String[]{
                "GPS Only (Outdoor)",
                "Indoor Assist"
        };
        int checked = cameraPrefs.isIndoorAssistEnabled() ? 1 : 0;

        new MaterialAlertDialogBuilder(this)
                .setTitle("GPS Mode")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    cameraPrefs.saveIndoorAssistEnabled(which == 1);
                    refreshValues();
                    dialog.dismiss();
                    Toast.makeText(
                            this,
                            which == 1 ? "Indoor Assist enabled" : "GPS Only enabled",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFlashDialog() {
        String[] options = new String[]{"Automatic", "On", "Off"};
        String mode = cameraPrefs.getFlashMode();
        int checked = CameraPrefs.FLASH_ON.equals(mode)
                ? 1
                : (CameraPrefs.FLASH_OFF.equals(mode) ? 2 : 0);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Camera Flash")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    String selected = which == 1
                            ? CameraPrefs.FLASH_ON
                            : (which == 2 ? CameraPrefs.FLASH_OFF : CameraPrefs.FLASH_AUTO);
                    CameraFlashController.setMode(this, selected);
                    refreshValues();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmResetCameraSettings() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Reset Camera Settings")
                .setMessage("Reset GPS mode to GPS Only and camera flash to Automatic?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dialog, which) -> {
                    cameraPrefs.saveIndoorAssistEnabled(false);
                    CameraFlashController.setMode(this, CameraPrefs.FLASH_AUTO);
                    refreshValues();
                    Toast.makeText(this, "Camera settings reset", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void refreshValues() {
        if (tvGpsModeValue != null) {
            tvGpsModeValue.setText(
                    cameraPrefs.isIndoorAssistEnabled() ? "INDOOR ASSIST" : "GPS ONLY"
            );
        }

        if (tvFlashValue != null) {
            String mode = cameraPrefs.getFlashMode();
            if (CameraPrefs.FLASH_ON.equals(mode)) tvFlashValue.setText("ON");
            else if (CameraPrefs.FLASH_OFF.equals(mode)) tvFlashValue.setText("OFF");
            else tvFlashValue.setText("AUTO");
        }

        if (tvCurrentProject != null) {
            tvCurrentProject.setText(currentCaptureLabel());
        }
    }

    private String currentCaptureLabel() {
        String docType = cameraPrefs.getDocumentationType();

        if (CameraPrefs.DOC_PERSONAL.equals(docType) || cameraPrefs.isUncategorized()) {
            return "Personal Capture";
        }

        String projectId = CameraPrefs.DOC_PROJECT_ACTIVITY.equals(docType)
                ? cameraPrefs.getActivityProjectId()
                : cameraPrefs.getSiteId();

        if (projectId == null || projectId.trim().isEmpty()) {
            return "Not selected";
        }

        String label = projectRepo.getProjectDisplayLabel(projectId);
        if (label == null || label.trim().isEmpty()) label = projectId;

        if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(docType)) {
            return "Project Activity • " + label;
        }
        return "Infrastructure • " + label;
    }

    private void bindVersion() {
        if (tvAppVersion == null) return;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = info.versionName;
            tvAppVersion.setText(version == null || version.trim().isEmpty() ? "-" : version);
        } catch (Exception ignored) {
            tvAppVersion.setText("-");
        }
    }
}
