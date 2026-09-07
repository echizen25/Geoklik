package ph.gov.geocamera.presentation.geocamera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.CaptureContextRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;

/**
 * Local-only selector for Infrastructure vs Project Activity.
 *
 * Infrastructure keeps GeoKlik's existing Project/Site flow. Project Activity
 * stores a local Project ID and remains excluded from the current API sync.
 */
public class DocumentationModeChip extends MaterialButton {

    private CameraPrefs cameraPrefs;
    private CaptureContextRepository captureContextRepo;
    private ProjectRepository projectRepo;

    private boolean initialCheckDone = false;
    private boolean recreatePosted = false;
    private View captureButton;
    private AlertDialog activeDialog;

    private final Runnable initialPromptRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow() || initialCheckDone) return;

            Activity activity = findActivity(getContext());
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

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

    public DocumentationModeChip(@NonNull Context context, @Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DocumentationModeChip(@NonNull Context context,
                                 @Nullable android.util.AttributeSet attrs,
                                 int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        cameraPrefs = new CameraPrefs(context);
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
        hideKeyboard(this);
        super.onDetachedFromWindow();
    }

    private void hookCaptureButton() {
        View root = getRootView();
        if (root == null) return;

        captureButton = root.findViewById(R.id.btnCapture);
        if (captureButton == null) return;

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

            captureContextRepo.snapshotForCapture(type, cameraPrefs.getActivityProjectId());
            return false;
        });
    }

    public void showDocumentationSettings() {
        if (isDialogOpen()) return;

        String type = cameraPrefs.getDocumentationType();
        if (type == null) {
            showDocumentationTypeChooser(true);
            return;
        }

        View content = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_documentation_settings, null, false);

        TextView tvMode = content.findViewById(R.id.tvCurrentDocMode);
        TextView tvProject = content.findViewById(R.id.tvCurrentActivityProject);
        MaterialButton btnSwitch = content.findViewById(R.id.btnChangeDocumentationType);
        MaterialButton btnProject = content.findViewById(R.id.btnChangeActivityProject);

        boolean activityMode = CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type);
        tvMode.setText(activityMode ? "Project Activity" : "Infrastructure");

        if (activityMode) {
            String id = cameraPrefs.getActivityProjectId();
            String label = getActivityDisplayLabel(id);
            tvProject.setVisibility(View.VISIBLE);
            tvProject.setText(label == null ? "No project selected" : label);
            btnProject.setVisibility(View.VISIBLE);
        } else {
            tvProject.setVisibility(View.GONE);
            btnProject.setVisibility(View.GONE);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
                .setView(content)
                .setNegativeButton("Close", null)
                .create();
        trackDialog(dialog);

        btnSwitch.setOnClickListener(v -> {
            dialog.dismiss();
            postDelayed(() -> showDocumentationTypeChooser(false), 100);
        });

        btnProject.setOnClickListener(v -> {
            dialog.dismiss();
            postDelayed(() -> showActivityProjectChooser(false), 100);
        });

        dialog.show();
    }

    public void showActivityProjectSettings() {
        if (isDialogOpen()) return;
        if (!CameraPrefs.DOC_PROJECT_ACTIVITY.equals(cameraPrefs.getDocumentationType())) {
            showDocumentationSettings();
            return;
        }
        showActivityProjectChooser(false);
    }

    private void showDocumentationTypeChooser(boolean required) {
        if (isDialogOpen()) return;

        final String previousType = cameraPrefs.getDocumentationType();
        View content = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_documentation_type, null, false);

        View infra = content.findViewById(R.id.optionInfrastructure);
        View activity = content.findViewById(R.id.optionProjectActivity);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext())
                .setView(content)
                .setCancelable(!required);
        if (!required) builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!required);
        trackDialog(dialog);

        infra.setOnClickListener(v -> {
            cameraPrefs.clearDocumentationPlaceholderIfPresent();

            if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(previousType)) {
                cameraPrefs.restoreInfrastructureSelection();
            }

            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_INFRA);
            captureContextRepo.setCurrent(CameraPrefs.DOC_INFRA, null);
            refreshLabel();
            dialog.dismiss();

            if (!CameraPrefs.DOC_INFRA.equals(previousType)) {
                postDelayed(this::recreateCameraOnce, 100);
            }
        });

        activity.setOnClickListener(v -> {
            cameraPrefs.clearDocumentationPlaceholderIfPresent();

            if (!CameraPrefs.DOC_PROJECT_ACTIVITY.equals(previousType)) {
                cameraPrefs.rememberInfrastructureSelection();
            }

            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);
            refreshLabel();
            dialog.dismiss();

            if (cameraPrefs.hasActivityProjectId()) {
                applyCurrentMode(false);
                postDelayed(this::recreateCameraOnce, 100);
            } else {
                postDelayed(() -> showActivityProjectChooser(true), 120);
            }
        });

        dialog.show();
    }

    private void showActivityProjectChooser(boolean required) {
        if (isDialogOpen()) return;

        View content = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_activity_project, null, false);

        TextInputLayout til = content.findViewById(R.id.tilActivityProject);
        MaterialAutoCompleteTextView input = content.findViewById(R.id.etActivityProject);
        TextView resolvedLabel = content.findViewById(R.id.tvProjectResolved);
        MaterialButton btnUse = content.findViewById(R.id.btnUseActivityProject);

        input.setSingleLine(true);
        input.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);

        List<String> suggestions = projectRepo.getProjectSuggestions("", 100);
        input.setAdapter(new ArrayAdapter<>(
                getContext(),
                android.R.layout.simple_dropdown_item_1line,
                suggestions
        ));
        input.setThreshold(0);
        input.setOnClickListener(v -> input.showDropDown());
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !suggestions.isEmpty()) input.postDelayed(input::showDropDown, 120);
        });

        String current = cameraPrefs.getActivityProjectId();
        if (current != null && !current.isEmpty()) {
            input.setText(current, false);
            updateResolvedProjectLabel(resolvedLabel, current);
        }

        input.setOnItemClickListener((parent, view, position, id) -> {
            String raw = input.getText() == null ? "" : input.getText().toString().trim();
            updateResolvedProjectLabel(resolvedLabel, raw);
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext())
                .setView(content)
                .setCancelable(!required);
        if (!required) builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!required);
        trackDialog(dialog);

        btnUse.setOnClickListener(v -> submitActivityProject(input, til, dialog));

        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean imeDone = actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEARCH;
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (!imeDone && !enter) return false;

            submitActivityProject(input, til, dialog);
            return true;
        });

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });

        dialog.show();
    }

    private void submitActivityProject(MaterialAutoCompleteTextView input,
                                       TextInputLayout til,
                                       AlertDialog dialog) {
        String raw = input.getText() == null ? "" : input.getText().toString().trim();
        if (raw.isEmpty()) {
            til.setError("Enter or select a Project ID");
            input.requestFocus();
            return;
        }

        String resolved = projectRepo.resolveProjectId(raw);
        String projectId = (resolved == null || resolved.trim().isEmpty())
                ? raw
                : resolved.trim();

        til.setError(null);
        cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);
        cameraPrefs.saveActivityProjectId(projectId);
        cameraPrefs.saveSite(projectId, false);
        captureContextRepo.setCurrent(CameraPrefs.DOC_PROJECT_ACTIVITY, projectId);

        hideKeyboard(input);
        refreshLabel();
        dialog.dismiss();
        postDelayed(this::recreateCameraOnce, 100);
    }

    private void updateResolvedProjectLabel(TextView view, String raw) {
        if (view == null) return;
        String resolved = projectRepo.resolveProjectId(raw);
        if (resolved == null || resolved.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }

        String label = getActivityDisplayLabel(resolved);
        if (label == null || label.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }

        view.setText("Selected: " + label);
        view.setVisibility(View.VISIBLE);
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
            setText("CHOOSE MODE");
            return;
        }

        if (CameraPrefs.DOC_INFRA.equals(type)) {
            setText("MODE  •  INFRASTRUCTURE");
            setIconResource(R.drawable.ic_infrastructure_24);
            return;
        }

        String projectId = cameraPrefs.getActivityProjectId();
        String label = getActivityDisplayLabel(projectId);
        if (label == null || label.isEmpty()) {
            setText("MODE  •  PROJECT ACTIVITY");
        } else {
            setText("ACTIVITY  •  " + compactLabel(label));
        }
        setIconResource(R.drawable.ic_project_activity_24);
    }

    private String getActivityDisplayLabel(String projectId) {
        if (projectId == null || projectId.trim().isEmpty()) return null;
        String full = projectRepo.getProjectDisplayLabel(projectId.trim());
        if (full == null || full.trim().isEmpty()) return projectId.trim();

        String value = full.trim();
        int sep = value.indexOf(" — ");
        if (sep >= 0 && sep + 3 < value.length()) {
            String name = value.substring(sep + 3).trim();
            if (!name.isEmpty()) return name;
        }
        return value;
    }

    private String compactLabel(String value) {
        if (value == null) return "PROJECT ACTIVITY";
        String v = value.trim();
        return v.length() <= 24 ? v : v.substring(0, 23) + "…";
    }

    private boolean isDialogOpen() {
        return activeDialog != null && activeDialog.isShowing();
    }

    private void trackDialog(AlertDialog dialog) {
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDialog == dialog) activeDialog = null;
            refreshLabel();
        });
    }

    private void hideKeyboard(View view) {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && view != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
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
