package ph.gov.geocamera.presentation.settings;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.data.repository.UserRepository;
import ph.gov.geocamera.presentation.common.BaseTopAppBarActivity;
import ph.gov.geocamera.presentation.geocamera.CameraFlashController;
import ph.gov.geocamera.presentation.geocamera.GeoCameraActivity;
import ph.gov.geocamera.presentation.home.HomeActivity;
import ph.gov.geocamera.presentation.site.SetSiteActivity;

public class SettingsActivity extends BaseTopAppBarActivity {

    private static final String[] PROJECT_PROGRAM_OPTIONS = new String[]{
            "RCEF",
            "CFIDP",
            "PHILMECH"
    };

    private CameraPrefs cameraPrefs;
    private ProjectRepository projectRepo;
    private UserRepository userRepo;

    private TextView tvCurrentProject;
    private TextView tvGpsModeValue;
    private TextView tvFlashValue;
    private TextView tvAppVersion;
    private TextView tvProfileName;
    private TextView tvProfileDesignation;
    private TextView tvProfileProject;

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
        userRepo = new UserRepository(this);

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

        tvProfileName = findViewById(R.id.tvProfileName);
        tvProfileDesignation = findViewById(R.id.tvProfileDesignation);
        tvProfileProject = findViewById(R.id.tvProfileProject);
        tvCurrentProject = findViewById(R.id.tvCurrentProject);
        tvGpsModeValue = findViewById(R.id.tvGpsModeValue);
        tvFlashValue = findViewById(R.id.tvFlashValue);
        tvAppVersion = findViewById(R.id.tvAppVersion);

        View rowEditProfile = findViewById(R.id.rowEditProfile);
        View rowChangeProject = findViewById(R.id.rowChangeProject);
        View rowGpsMode = findViewById(R.id.rowGpsMode);
        View rowFlash = findViewById(R.id.rowFlash);
        View rowResetCamera = findViewById(R.id.rowResetCamera);

        if (rowEditProfile != null) {
            rowEditProfile.setOnClickListener(v -> showEditProfileDialog());
        }

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

    private void showEditProfileDialog() {
        UserRepository.UserProfile profile = userRepo.getProfile();
        if (profile == null) {
            Toast.makeText(this, "No local user profile found.", Toast.LENGTH_LONG).show();
            return;
        }

        int pad = dp(18);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, dp(6), pad, 0);

        TextInputEditText etFirst = addField(container, "First name", profile.firstName, false);
        TextInputEditText etMiddle = addField(container, "Middle name", profile.middleName, false);
        TextInputEditText etLast = addField(container, "Last name", profile.lastName, false);
        TextInputEditText etDesignation = addField(container, "Designation", profile.designation, false);
        MaterialAutoCompleteTextView actProject = addProjectProgramField(container, profile.project);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Edit Profile")
                .setMessage("These details are used by GeoKlik for local user identification and camera overlays.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String first = textOf(etFirst);
                    String middle = textOf(etMiddle);
                    String last = textOf(etLast);
                    String designation = textOf(etDesignation);
                    String project = textOf(actProject);

                    if (first.isEmpty() || last.isEmpty()) {
                        Toast.makeText(this, "First name and last name are required.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!isValidProjectProgram(project)) {
                        Toast.makeText(this, "Select RCEF, CFIDP, or PHILMECH.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    boolean saved = userRepo.updateProfile(
                            first, middle, last, designation, project
                    );
                    if (!saved) {
                        Toast.makeText(this, "Profile could not be updated.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    dialog.dismiss();
                    refreshProfile();
                    Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
                }));
        dialog.show();
    }

    private TextInputEditText addField(LinearLayout parent,
                                       String hint,
                                       String value,
                                       boolean caps) {
        TextInputLayout til = new TextInputLayout(this);
        til.setHint(hint);
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

        TextInputEditText edit = new TextInputEditText(this);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT |
                (caps ? InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        : InputType.TYPE_TEXT_FLAG_CAP_WORDS));
        edit.setText(value == null ? "" : value);
        til.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = parent.getChildCount() == 0 ? 0 : dp(10);
        parent.addView(til, lp);
        return edit;
    }

    private MaterialAutoCompleteTextView addProjectProgramField(LinearLayout parent, String value) {
        TextInputLayout til = new TextInputLayout(this);
        til.setHint("Project / Program");
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        til.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);

        MaterialAutoCompleteTextView dropdown = new MaterialAutoCompleteTextView(this);
        dropdown.setSingleLine(true);
        dropdown.setInputType(InputType.TYPE_NULL);
        dropdown.setKeyListener(null);
        dropdown.setCursorVisible(false);
        dropdown.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                PROJECT_PROGRAM_OPTIONS
        ));

        String normalized = normalizeProjectProgram(value);
        if (!normalized.isEmpty()) dropdown.setText(normalized, false);

        til.addView(dropdown, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = parent.getChildCount() == 0 ? 0 : dp(10);
        parent.addView(til, lp);
        return dropdown;
    }

    private String normalizeProjectProgram(String value) {
        if (value == null) return "";
        String cleaned = value.trim().toUpperCase();
        if ("RCEF".equals(cleaned)) return "RCEF";
        if ("CFIDP".equals(cleaned) || "CTF".equals(cleaned)) return "CFIDP";
        if ("PHILMECH".equals(cleaned)) return "PHILMECH";
        return "";
    }

    private boolean isValidProjectProgram(String value) {
        return !normalizeProjectProgram(value).isEmpty();
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
        refreshProfile();

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

    private void refreshProfile() {
        UserRepository.UserProfile profile = userRepo == null ? null : userRepo.getProfile();

        if (profile == null) {
            if (tvProfileName != null) tvProfileName.setText("No local profile");
            if (tvProfileDesignation != null) tvProfileDesignation.setText("Complete user setup first");
            if (tvProfileProject != null) tvProfileProject.setText("-");
            return;
        }

        String fullName = profile.fullName();
        if (tvProfileName != null) {
            tvProfileName.setText(fullName.isEmpty() ? "GeoKlik User" : fullName);
        }
        if (tvProfileDesignation != null) {
            tvProfileDesignation.setText(
                    profile.designation == null || profile.designation.trim().isEmpty()
                            ? "No designation"
                            : profile.designation.trim()
            );
        }
        if (tvProfileProject != null) {
            tvProfileProject.setText(
                    profile.project == null || profile.project.trim().isEmpty()
                            ? "No project assigned"
                            : profile.project.trim()
            );
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

    private static String textOf(TextView view) {
        return view == null || view.getText() == null ? "" : view.getText().toString().trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
