package ph.gov.geocamera.presentation.geocamera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.CaptureContextRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;

/**
 * Lightweight local documentation-mode control.
 *
 * First camera use asks only Infrastructure vs Project Activity. The choice is
 * remembered. Infrastructure keeps the existing Project/Site flow unchanged.
 * Project Activity asks for a Project ID once and uses that ID as the local
 * grouping key. No shot-type prompt is used anymore.
 */
public class DocumentationModeChip extends MaterialButton {

    private CameraPrefs cameraPrefs;
    private CaptureContextRepository captureContextRepo;
    private ProjectRepository projectRepo;

    private boolean initialCheckDone = false;
    private boolean dialogShowing = false;
    private boolean recreatePosted = false;
    private View captureButton;

    private final Runnable initialPromptRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow() || initialCheckDone) return;

            Activity activity = findActivity(getContext());
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

            // Wait only for Android permission dialogs. The temporary selection
            // placeholder prevents the legacy Site picker from jumping ahead of
            // this first Infrastructure / Project Activity question.
            if (!activity.hasWindowFocus()) {
                postDelayed(this, 250);
                return;
            }

            initialCheckDone = true;

            if (!cameraPrefs.hasDocumentationType()) {
                showDocumentationTypeChooser(true);
                return;
            }

            if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(cameraPrefs.getDocumentationType())
                    && !cameraPrefs.hasActivityProjectId()) {
                showActivityProjectChooser(true);
                return;
            }

            applyCurrentMode(true);
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

        // This runs during setContentView(), before GeoCameraActivity performs
        // its legacy first-site check. It does not alter a real saved selection.
        cameraPrefs.primeDocumentationSelectionPlaceholder();

        captureContextRepo = new CaptureContextRepository(context);
        projectRepo = new ProjectRepository(context);

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

        // Existing shutter click stays untouched. This listener only snapshots
        // the local metadata immediately before the normal capture begins.
        captureButton.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_DOWN) return false;

            if (!cameraPrefs.hasDocumentationType()) {
                showDocumentationTypeChooser(true);
                return true;
            }

            String type = cameraPrefs.getDocumentationType();
            if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type)
                    && !cameraPrefs.hasActivityProjectId()) {
                showActivityProjectChooser(true);
                return true;
            }

            captureContextRepo.snapshotForCapture(
                    type,
                    cameraPrefs.getActivityProjectId()
            );
            return false;
        });
    }

    private void showDocumentationSettings() {
        if (dialogShowing) return;

        String type = cameraPrefs.getDocumentationType();
        if (type == null) {
            showDocumentationTypeChooser(true);
            return;
        }

        if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type)) {
            String projectId = cameraPrefs.getActivityProjectId();
            new MaterialAlertDialogBuilder(getContext())
                    .setTitle("Documentation Settings")
                    .setItems(new String[]{
                            "Documentation Type: Project Activity",
                            "Project ID: " + (projectId == null ? "Not selected" : projectId)
                    }, (dialog, which) -> {
                        if (which == 0) showDocumentationTypeChooser(false);
                        else showActivityProjectChooser(false);
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } else {
            new MaterialAlertDialogBuilder(getContext())
                    .setTitle("Documentation Settings")
                    .setItems(new String[]{
                            "Documentation Type: Infrastructure"
                    }, (dialog, which) -> showDocumentationTypeChooser(false))
                    .setNegativeButton("Close", null)
                    .show();
        }
    }

    private void showDocumentationTypeChooser(boolean required) {
        if (dialogShowing) return;
        dialogShowing = true;

        final String previousType = cameraPrefs.getDocumentationType();
        final String[] labels = new String[]{
                "Infrastructure\nUse the existing Project / Site field documentation workflow",
                "Project Activity\nTraining, field activity, demo, meeting, event or similar documentation"
        };

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext())
                .setTitle("What are you documenting?")
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        // On the very first use, remove the temporary blocker so
                        // the existing Infrastructure Project/Site picker can run.
                        cameraPrefs.clearDocumentationPlaceholderIfPresent();
                        cameraPrefs.saveDocumentationType(CameraPrefs.DOC_INFRA);

                        // If coming back from Activity mode, restore the previous
                        // Infrastructure Project/Site instead of losing it.
                        if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(previousType)) {
                            cameraPrefs.restoreInfrastructureSelection();
                        }

                        captureContextRepo.setCurrent(CameraPrefs.DOC_INFRA, null);
                        refreshLabel();

                        // Reload GeoCameraActivity so its existing Project/Site
                        // logic sees the restored/cleared Infrastructure selection.
                        if (!CameraPrefs.DOC_INFRA.equals(previousType)) {
                            post(this::recreateCameraOnce);
                        }
                    } else {
                        // Do not save the internal placeholder as an Infra site.
                        cameraPrefs.clearDocumentationPlaceholderIfPresent();

                        if (!CameraPrefs.DOC_PROJECT_ACTIVITY.equals(previousType)) {
                            cameraPrefs.rememberInfrastructureSelection();
                        }

                        cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);
                        refreshLabel();

                        // The type chooser closes first; then ask for Project ID.
                        postDelayed(() -> showActivityProjectChooser(true), 120);
                    }
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

    private void showActivityProjectChooser(boolean required) {
        if (dialogShowing) {
            postDelayed(() -> showActivityProjectChooser(required), 120);
            return;
        }
        dialogShowing = true;

        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(8), dp(24), 0);

        AutoCompleteTextView input = new AutoCompleteTextView(getContext());
        input.setSingleLine(true);
        input.setHint("Project ID / code / project name");
        input.setThreshold(0);

        List<String> suggestions = projectRepo.getProjectSuggestions("", 100);
        input.setAdapter(new ArrayAdapter<>(
                getContext(),
                android.R.layout.simple_dropdown_item_1line,
                suggestions
        ));
        input.setOnClickListener(v -> input.showDropDown());

        String current = cameraPrefs.getActivityProjectId();
        if (current != null && !current.isEmpty()) input.setText(current, false);

        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView note = new TextView(getContext());
        note.setText("Saved locally only for now. Project Activity photos will not be sent to the current API yet.");
        note.setTextSize(12f);
        note.setPadding(0, dp(10), 0, 0);
        container.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext())
                .setTitle("Project Activity")
                .setMessage("Select an existing project or enter its Project ID.")
                .setView(container)
                .setPositiveButton("Use Project", null);

        if (!required) builder.setNegativeButton("Cancel", null);
        builder.setCancelable(!required);

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!required);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText() == null ? "" : input.getText().toString().trim();
            if (raw.isEmpty()) {
                input.setError("Enter or select a Project ID");
                return;
            }

            String resolved = projectRepo.resolveProjectId(raw);
            String projectId = (resolved == null || resolved.trim().isEmpty())
                    ? raw
                    : resolved.trim();

            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);
            cameraPrefs.saveActivityProjectId(projectId);

            // Reuse the existing local site/group key so the current
            // Gallery → Date → Photos hierarchy continues to work.
            // DB v116 marks PROJECT_ACTIVITY captures LOCAL_ONLY, so this does
            // not send the new activity metadata to the current API.
            cameraPrefs.saveSite(projectId, false);

            captureContextRepo.setCurrent(CameraPrefs.DOC_PROJECT_ACTIVITY, projectId);
            refreshLabel();
            dialog.dismiss();
            recreateCameraOnce();
        }));

        dialog.setOnDismissListener(d -> {
            dialogShowing = false;
            refreshLabel();
        });
        dialog.show();
    }

    private void applyCurrentMode(boolean recreateIfNeeded) {
        String type = cameraPrefs.getDocumentationType();

        if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type)) {
            String projectId = cameraPrefs.getActivityProjectId();
            if (projectId == null || projectId.isEmpty()) return;

            boolean mismatch = cameraPrefs.isUncategorized()
                    || !projectId.equals(cameraPrefs.getSiteId());
            if (mismatch) cameraPrefs.saveSite(projectId, false);

            captureContextRepo.setCurrent(type, projectId);
            if (mismatch && recreateIfNeeded) recreateCameraOnce();
        } else if (CameraPrefs.DOC_INFRA.equals(type)) {
            cameraPrefs.clearDocumentationPlaceholderIfPresent();
            captureContextRepo.setCurrent(type, null);
        }

        refreshLabel();
    }

    private void refreshLabel() {
        String type = cameraPrefs.getDocumentationType();
        if (type == null) {
            setText("CHOOSE TYPE");
            return;
        }

        if (CameraPrefs.DOC_INFRA.equals(type)) {
            setText("INFRASTRUCTURE");
            return;
        }

        String projectId = cameraPrefs.getActivityProjectId();
        if (projectId == null || projectId.isEmpty()) setText("PROJECT ACTIVITY");
        else setText("ACTIVITY  •  " + projectId);
    }

    private void recreateCameraOnce() {
        if (recreatePosted) return;
        Activity activity = findActivity(getContext());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        recreatePosted = true;
        postDelayed(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) activity.recreate();
        }, 120);
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
