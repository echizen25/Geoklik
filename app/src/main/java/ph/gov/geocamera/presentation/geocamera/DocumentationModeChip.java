package ph.gov.geocamera.presentation.geocamera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.CaptureContextRepository;

/**
 * Presentation-only control for classifying a capture as Infrastructure or
 * Project Activity and assigning a local shot type.
 *
 * The selected values are local-only for now. Existing CameraX, GPS,
 * watermark, upload, and API behavior remain untouched.
 */
public class DocumentationModeChip extends MaterialButton {

    private CameraPrefs cameraPrefs;
    private CaptureContextRepository captureContextRepo;

    private boolean initialPromptShown = false;
    private boolean dialogShowing = false;
    private View captureButton;

    private final Runnable initialPromptRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow() || initialPromptShown) return;

            Activity activity = findActivity(getContext());
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

            // Wait for Android permission dialogs / site selector to finish first.
            // Once this camera window owns focus, require the documentation type.
            if (!activity.hasWindowFocus()) {
                postDelayed(this, 250);
                return;
            }

            initialPromptShown = true;
            showDocumentationTypeChooser(true);
        }
    };

    public DocumentationModeChip(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DocumentationModeChip(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DocumentationModeChip(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        cameraPrefs = new CameraPrefs(context);
        captureContextRepo = new CaptureContextRepository(context);
        setAllCaps(false);
        setOnClickListener(v -> showDocumentationSettings());
        refreshLabel();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        hookCaptureButton();
        refreshLabel();
        removeCallbacks(initialPromptRunnable);
        postDelayed(initialPromptRunnable, 250);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(initialPromptRunnable);
        super.onDetachedFromWindow();
    }

    private void hookCaptureButton() {
        View root = getRootView();
        if (root == null) return;

        captureButton = root.findViewById(R.id.btnCapture);
        if (captureButton == null) return;

        // Do not consume valid shutter touches. We only snapshot local metadata
        // before GeoCameraActivity's existing click handler performs the capture.
        captureButton.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_DOWN) return false;

            if (!cameraPrefs.hasDocumentationType()) {
                showDocumentationTypeChooser(true);
                return true;
            }

            captureContextRepo.snapshotForCapture(
                    cameraPrefs.getDocumentationType(),
                    cameraPrefs.getShotType()
            );
            return false;
        });
    }

    private void showDocumentationSettings() {
        if (dialogShowing) return;

        String typeLabel = documentationTypeLabel(cameraPrefs.getDocumentationType());
        String shotLabel = shotLabel(cameraPrefs.getShotType());

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Documentation Settings")
                .setItems(new String[]{
                        "Documentation Type: " + typeLabel,
                        "Shot Type: " + shotLabel
                }, (dialog, which) -> {
                    if (which == 0) {
                        showDocumentationTypeChooser(false);
                    } else if (which == 1) {
                        showShotTypeChooser();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showDocumentationTypeChooser(boolean required) {
        if (dialogShowing) return;
        dialogShowing = true;

        final String[] labels = new String[]{
                "Infrastructure\nBuildings, facilities, installations and construction-related documentation",
                "Project Activity\nTrainings, field activities, demonstrations, meetings and events"
        };

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext())
                .setTitle("What are you documenting?")
                .setItems(labels, (dialog, which) -> {
                    String type = which == 0
                            ? CameraPrefs.DOC_INFRA
                            : CameraPrefs.DOC_PROJECT_ACTIVITY;

                    cameraPrefs.saveDocumentationType(type);
                    cameraPrefs.saveShotType(CameraPrefs.SHOT_GENERAL);
                    captureContextRepo.setCurrent(type, CameraPrefs.SHOT_GENERAL);
                    refreshLabel();
                });

        if (required) {
            builder.setCancelable(false);
        } else {
            builder.setNegativeButton("Cancel", null);
        }

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            dialogShowing = false;
            refreshLabel();
        });
        dialog.setCanceledOnTouchOutside(!required);
        dialog.show();
    }

    private void showShotTypeChooser() {
        if (dialogShowing) return;
        if (!cameraPrefs.hasDocumentationType()) {
            showDocumentationTypeChooser(true);
            return;
        }

        dialogShowing = true;
        String type = cameraPrefs.getDocumentationType();
        final String[] labels;
        final String[] values;

        if (CameraPrefs.DOC_INFRA.equals(type)) {
            labels = new String[]{
                    "General",
                    "Front View",
                    "Side View",
                    "Rear View",
                    "Interior",
                    "Site / Area",
                    "Detail / Close-up",
                    "Equipment / Installation",
                    "Other"
            };
            values = new String[]{
                    "GENERAL",
                    "FRONT_VIEW",
                    "SIDE_VIEW",
                    "REAR_VIEW",
                    "INTERIOR",
                    "SITE_AREA",
                    "DETAIL_CLOSEUP",
                    "EQUIPMENT_INSTALLATION",
                    "OTHER"
            };
        } else {
            labels = new String[]{
                    "General",
                    "Venue",
                    "Participants",
                    "Registration",
                    "Actual Activity",
                    "Equipment / Materials",
                    "Output / Result",
                    "Group Photo",
                    "Other"
            };
            values = new String[]{
                    "GENERAL",
                    "VENUE",
                    "PARTICIPANTS",
                    "REGISTRATION",
                    "ACTUAL_ACTIVITY",
                    "EQUIPMENT_MATERIALS",
                    "OUTPUT_RESULT",
                    "GROUP_PHOTO",
                    "OTHER"
            };
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
                .setTitle("Choose Shot Type")
                .setItems(labels, (d, which) -> {
                    String shot = values[which];
                    cameraPrefs.saveShotType(shot);
                    captureContextRepo.setCurrent(cameraPrefs.getDocumentationType(), shot);
                    refreshLabel();
                })
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnDismissListener(d -> {
            dialogShowing = false;
            refreshLabel();
        });
        dialog.show();
    }

    private void refreshLabel() {
        if (!cameraPrefs.hasDocumentationType()) {
            setText("TYPE / SHOT");
            return;
        }

        String type = cameraPrefs.getDocumentationType();
        String shortType = CameraPrefs.DOC_INFRA.equals(type) ? "INFRA" : "ACTIVITY";
        setText(shortType + "  •  " + shotLabel(cameraPrefs.getShotType()));
    }

    private String documentationTypeLabel(String type) {
        return CameraPrefs.DOC_INFRA.equals(type) ? "Infrastructure" : "Project Activity";
    }

    private String shotLabel(String shot) {
        if (shot == null || shot.trim().isEmpty()) return "General";
        String normalized = shot.trim().toUpperCase(Locale.US);
        switch (normalized) {
            case "FRONT_VIEW": return "Front View";
            case "SIDE_VIEW": return "Side View";
            case "REAR_VIEW": return "Rear View";
            case "INTERIOR": return "Interior";
            case "SITE_AREA": return "Site / Area";
            case "DETAIL_CLOSEUP": return "Detail / Close-up";
            case "EQUIPMENT_INSTALLATION": return "Equipment / Installation";
            case "VENUE": return "Venue";
            case "PARTICIPANTS": return "Participants";
            case "REGISTRATION": return "Registration";
            case "ACTUAL_ACTIVITY": return "Actual Activity";
            case "EQUIPMENT_MATERIALS": return "Equipment / Materials";
            case "OUTPUT_RESULT": return "Output / Result";
            case "GROUP_PHOTO": return "Group Photo";
            case "OTHER": return "Other";
            default: return "General";
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
