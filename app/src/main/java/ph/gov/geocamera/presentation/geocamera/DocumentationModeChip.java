package ph.gov.geocamera.presentation.geocamera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.CaptureContextRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.presentation.site.SetSiteActivity;

/** Displays the capture classification derived from the selected target. */
public class DocumentationModeChip extends MaterialButton {

    private CameraPrefs cameraPrefs;
    private CaptureContextRepository captureContextRepo;
    private ProjectRepository projectRepo;
    private View captureButton;

    public DocumentationModeChip(@NonNull Context context) { super(context); init(context); }
    public DocumentationModeChip(@NonNull Context context, @Nullable android.util.AttributeSet attrs) { super(context, attrs); init(context); }
    public DocumentationModeChip(@NonNull Context context, @Nullable android.util.AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(context); }

    private void init(Context context) {
        cameraPrefs = new CameraPrefs(context);
        cameraPrefs.clearDocumentationPlaceholderIfPresent();
        captureContextRepo = new CaptureContextRepository(context);
        projectRepo = new ProjectRepository(context);
        setAllCaps(false);
        setOnClickListener(v -> openProjectPicker());
        refreshFromSelection();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); hookCaptureButton(); refreshFromSelection(); }
    @Override public void onWindowFocusChanged(boolean hasWindowFocus) { super.onWindowFocusChanged(hasWindowFocus); if (hasWindowFocus) refreshFromSelection(); }

    private void hookCaptureButton() {
        View root = getRootView();
        if (root == null) return;
        captureButton = root.findViewById(R.id.btnCapture);
        if (captureButton == null) return;
        captureButton.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_DOWN) return false;
            if (!cameraPrefs.hasSelection()) return false;
            String type = syncDerivedClassification();
            String activityProjectId = CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type) ? cameraPrefs.getActivityProjectId() : null;
            captureContextRepo.snapshotForCapture(type, activityProjectId);
            return false;
        });
    }

    private void openProjectPicker() {
        Activity activity = findActivity(getContext());
        if (activity == null || activity.isFinishing()) return;
        activity.startActivity(new Intent(activity, SetSiteActivity.class));
    }

    private String syncDerivedClassification() {
        String explicitType = cameraPrefs.getDocumentationType();
        if (CameraPrefs.DOC_PERSONAL.equals(explicitType)) {
            cameraPrefs.clearActivityProjectId();
            captureContextRepo.setCurrent(CameraPrefs.DOC_PERSONAL, null);
            return CameraPrefs.DOC_PERSONAL;
        }
        if (!cameraPrefs.hasSelection()) {
            cameraPrefs.clearActivityProjectId();
            return explicitType == null ? CameraPrefs.DOC_INFRA : explicitType;
        }
        if (cameraPrefs.isUncategorized()) {
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PERSONAL);
            cameraPrefs.clearActivityProjectId();
            captureContextRepo.setCurrent(CameraPrefs.DOC_PERSONAL, null);
            return CameraPrefs.DOC_PERSONAL;
        }

        String projectId = cameraPrefs.getSiteId();
        String type = projectRepo.getProjectTypeById(projectId);

        if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type)) {
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);
            cameraPrefs.saveActivityProjectId(projectId);
            captureContextRepo.setCurrent(CameraPrefs.DOC_PROJECT_ACTIVITY, projectId);
            return CameraPrefs.DOC_PROJECT_ACTIVITY;
        }
        if (CameraPrefs.DOC_PROJECT.equals(type)) {
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PROJECT);
            cameraPrefs.clearActivityProjectId();
            captureContextRepo.setCurrent(CameraPrefs.DOC_PROJECT, null);
            return CameraPrefs.DOC_PROJECT;
        }
        if (CameraPrefs.DOC_ACTIVITY.equals(type)) {
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_ACTIVITY);
            cameraPrefs.clearActivityProjectId();
            captureContextRepo.setCurrent(CameraPrefs.DOC_ACTIVITY, null);
            return CameraPrefs.DOC_ACTIVITY;
        }

        // Keep existing INFRA and unknown/old records on the proven INFRA path.
        cameraPrefs.saveDocumentationType(CameraPrefs.DOC_INFRA);
        cameraPrefs.clearActivityProjectId();
        captureContextRepo.setCurrent(CameraPrefs.DOC_INFRA, null);
        return CameraPrefs.DOC_INFRA;
    }

    private void refreshFromSelection() {
        if (!cameraPrefs.hasSelection()) { setText("SELECT PROJECT"); setIcon(null); return; }
        String type = syncDerivedClassification();
        if (CameraPrefs.DOC_PERSONAL.equals(type)) {
            setText("PERSONAL CAPTURE");
            setIconResource(R.drawable.ic_photo_library_24);
            return;
        }
        String projectId = cameraPrefs.getSiteId();
        String code = projectRepo.getProjectCodeById(projectId);
        String label = compact(code == null || code.trim().isEmpty() ? projectId : code);
        if (CameraPrefs.DOC_PROJECT.equals(type)) {
            setText("PROJECT  •  " + label);
            setIconResource(R.drawable.ic_project_activity_24);
        } else if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type) || CameraPrefs.DOC_ACTIVITY.equals(type)) {
            setText("ACTIVITY  •  " + label);
            setIconResource(R.drawable.ic_project_activity_24);
        } else {
            setText("INFRA  •  " + label);
            setIconResource(R.drawable.ic_infrastructure_24);
        }
    }

    private String compact(String value) {
        if (value == null || value.trim().isEmpty()) return "PROJECT";
        String v = value.trim();
        return v.length() <= 24 ? v : v.substring(0, 23) + "…";
    }

    private Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof Activity ? (Activity) current : null;
    }
}
