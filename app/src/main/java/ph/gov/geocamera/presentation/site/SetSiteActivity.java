package ph.gov.geocamera.presentation.site;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.CaptureContextRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.data.sync.ProjectBackgroundSync;

public class SetSiteActivity extends ComponentActivity {

    public static final String EXTRA_SITE_ID = "EXTRA_SITE_ID";
    public static final String EXTRA_UNCATEGORIZED = "EXTRA_UNCATEGORIZED";
    public static final String EXTRA_PICK_ONLY = "EXTRA_PICK_ONLY";
    public static final String EXTRA_REQUIRED_PROJECT_TYPE = "EXTRA_REQUIRED_PROJECT_TYPE";
    public static final String EXTRA_SELECTED_PROJECT_TYPE = "EXTRA_SELECTED_PROJECT_TYPE";
    public static final String EXTRA_SELECTED_PROJECT_CODE = "EXTRA_SELECTED_PROJECT_CODE";

    private ProjectRepository projectRepo;
    private CaptureContextRepository captureContextRepo;
    private CameraPrefs cameraPrefs;

    private ActivityResultLauncher<ScanOptions> qrLauncher;
    private ActivityResultLauncher<String> qrImageLauncher;
    private MaterialAutoCompleteTextView actSite;
    private ArrayAdapter<String> projectSuggestionAdapter;

    private boolean pickOnly = false;
    private String requiredProjectType = "";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_set_site);

        projectRepo = new ProjectRepository(this);
        captureContextRepo = new CaptureContextRepository(this);
        cameraPrefs = new CameraPrefs(this);

        Intent request = getIntent();
        if (request != null) {
            pickOnly = request.getBooleanExtra(EXTRA_PICK_ONLY, false);
            requiredProjectType = normalizeProjectType(request.getStringExtra(EXTRA_REQUIRED_PROJECT_TYPE));
        }

        actSite = findViewById(R.id.actSite);
        MaterialButton btnUseSelected = findViewById(R.id.btnUseSelected);
        MaterialButton btnPasteCode = findViewById(R.id.btnPasteCode);
        MaterialButton btnScanQr = findViewById(R.id.btnScanQr);
        MaterialButton btnUploadQr = findViewById(R.id.btnUploadQr);
        MaterialButton btnUncategorized = findViewById(R.id.btnUncategorized);
        MaterialButton btnClose = findViewById(R.id.btnClose);
        View cardPersonalCapture = findViewById(R.id.cardPersonalCapture);
        TextView tvTitle = findViewById(R.id.tvTitle);
        TextView tvSubtitle = findViewById(R.id.tvSubtitle);

        if (pickOnly) {
            if (tvTitle != null) tvTitle.setText("Move Photos");
            if (tvSubtitle != null) {
                tvSubtitle.setText(CameraPrefs.DOC_INFRA.equals(requiredProjectType)
                        ? "Choose an Infrastructure project, paste a Project Code, or use QR."
                        : "Choose a project, paste a Project Code, or use QR.");
            }
            if (cardPersonalCapture != null) cardPersonalCapture.setVisibility(View.GONE);
            if (btnUncategorized != null) btnUncategorized.setVisibility(View.GONE);
            if (btnUseSelected != null) btnUseSelected.setText("Use Project");
        }

        setupProjectCodeInput();
        setupQrLaunchers();

        ProjectBackgroundSync.syncIfNeeded(this, false, updated -> runOnUiThread(() -> {
            if (updated && !currentProjectQuery().trim().isEmpty()) {
                refreshProjectSuggestions(currentProjectQuery(), false);
            }
        }));

        if (btnUseSelected != null) btnUseSelected.setOnClickListener(v -> submitCurrentProjectCode());
        if (btnPasteCode != null) btnPasteCode.setOnClickListener(v -> pasteProjectCodeFromClipboard());
        if (btnScanQr != null) btnScanQr.setOnClickListener(v -> startQrScan());
        if (btnUploadQr != null) btnUploadQr.setOnClickListener(v -> qrImageLauncher.launch("image/*"));
        if (btnUncategorized != null) btnUncategorized.setOnClickListener(v -> {
            if (!pickOnly) showPersonalCaptureDialog();
        });

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                if (pickOnly) {
                    setResult(RESULT_CANCELED);
                    finish();
                    return;
                }
                Intent i = new Intent(SetSiteActivity.this, ph.gov.geocamera.presentation.home.HomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
            });
        }
    }

    private void setupProjectCodeInput() {
        if (actSite == null) return;

        projectSuggestionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        actSite.setAdapter(projectSuggestionAdapter);
        actSite.setThreshold(1);
        actSite.setSingleLine(true);
        actSite.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);

        actSite.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s == null ? "" : s.toString().trim();
                if (query.isEmpty()) {
                    projectSuggestionAdapter.clear();
                    projectSuggestionAdapter.notifyDataSetChanged();
                    actSite.dismissDropDown();
                    return;
                }
                refreshProjectSuggestions(query, actSite.hasFocus());
            }
        });

        actSite.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !currentProjectQuery().trim().isEmpty()) {
                refreshProjectSuggestions(currentProjectQuery(), false);
            } else if (!hasFocus) {
                actSite.dismissDropDown();
            }
        });

        actSite.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent == null ? null : parent.getItemAtPosition(position);
            if (item == null) return;
            String selected = item.toString().trim();
            if (selected.isEmpty()) return;

            String projectId = null;
            try { projectId = projectRepo.resolveProjectId(selected); } catch (Exception ignored) {}

            String value = selected;
            if (projectId != null && !projectId.trim().isEmpty()) {
                String code = projectRepo.getProjectCodeById(projectId.trim());
                if (code != null && !code.trim().isEmpty()) value = code.trim();
                else value = projectId.trim();
            }

            actSite.setText(value, false);
            actSite.setSelection(value.length());
            actSite.dismissDropDown();
        });

        actSite.setOnEditorActionListener((v, actionId, event) -> {
            boolean isDone = actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_NEXT
                    || (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (!isDone) return false;
            submitCurrentProjectCode();
            return true;
        });
    }

    private String currentProjectQuery() {
        return actSite == null || actSite.getText() == null ? "" : actSite.getText().toString();
    }

    private void refreshProjectSuggestions(String query, boolean showDropdown) {
        if (actSite == null || projectSuggestionAdapter == null || projectRepo == null) return;
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.isEmpty()) {
            projectSuggestionAdapter.clear();
            projectSuggestionAdapter.notifyDataSetChanged();
            actSite.dismissDropDown();
            return;
        }

        List<String> suggestions;
        try {
            suggestions = projectRepo.getProjectSuggestions(cleanQuery, 6, requiredProjectType);
        } catch (Exception ignored) {
            suggestions = Collections.emptyList();
        }

        projectSuggestionAdapter.clear();
        if (suggestions != null && !suggestions.isEmpty()) projectSuggestionAdapter.addAll(suggestions);
        projectSuggestionAdapter.notifyDataSetChanged();

        if (showDropdown && actSite.hasFocus() && projectSuggestionAdapter.getCount() > 0) {
            actSite.post(actSite::showDropDown);
        } else if (projectSuggestionAdapter.getCount() == 0) {
            actSite.dismissDropDown();
        }
    }

    private void pasteProjectCodeFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence value = clip.getItemAt(0).coerceToText(this);
        String pasted = normalizeScannedValue(value == null ? "" : value.toString());
        if (pasted.isEmpty()) {
            Toast.makeText(this, "Clipboard does not contain a project code.", Toast.LENGTH_SHORT).show();
            return;
        }
        actSite.setText(pasted, false);
        actSite.setSelection(pasted.length());
        actSite.requestFocus();
        Toast.makeText(this, "Project code pasted", Toast.LENGTH_SHORT).show();
    }

    private void setupQrLaunchers() {
        qrLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() == null) return;
            applyQrValue(result.getContents(), "QR scanned");
        });

        qrImageLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            Toast.makeText(this, "Reading QR image…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String value = null;
                try { value = decodeQrFromImage(uri); } catch (Exception ignored) {}
                final String decoded = value;
                runOnUiThread(() -> {
                    if (decoded == null || decoded.trim().isEmpty()) {
                        Toast.makeText(this, "No readable QR code found in that image.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    applyQrValue(decoded, "QR image read");
                });
            }).start();
        });
    }

    private void applyQrValue(String value, String message) {
        String scanned = normalizeScannedValue(value);
        if (scanned.isEmpty()) {
            Toast.makeText(this, "Invalid QR content.", Toast.LENGTH_SHORT).show();
            return;
        }
        actSite.setText(scanned, false);
        actSite.setSelection(scanned.length());
        hideKeyboard();
        actSite.clearFocus();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> selectSiteFromInput(scanned), 120);
    }

    private void submitCurrentProjectCode() {
        hideKeyboard();
        actSite.dismissDropDown();
        actSite.clearFocus();
        String raw = actSite.getText() == null ? "" : actSite.getText().toString();
        raw = normalizeScannedValue(raw);
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter, paste, or scan a Project Code.", Toast.LENGTH_SHORT).show();
            return;
        }
        selectSiteFromInput(raw);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN && getCurrentFocus() != null) {
            hideKeyboard();
            getCurrentFocus().clearFocus();
        }
        return super.dispatchTouchEvent(ev);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            } else if (imm != null && actSite != null) {
                imm.hideSoftInputFromWindow(actSite.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }

    private void startQrScan() {
        hideKeyboard();
        if (actSite != null) {
            actSite.dismissDropDown();
            actSite.clearFocus();
        }
        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan Project QR");
        options.setBeepEnabled(true);
        options.setOrientationLocked(false);
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setCameraId(0);
        qrLauncher.launch(options);
    }

    private String normalizeScannedValue(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return "";
        s = s.replace("\n", " ").replace("\r", " ").trim();
        while (s.contains("  ")) s = s.replace("  ", " ");
        if (s.regionMatches(true, 0, "SITE:", 0, 5)) s = s.substring(5).trim();
        else if (s.regionMatches(true, 0, "PROJECT:", 0, 8)) s = s.substring(8).trim();
        else if (s.regionMatches(true, 0, "CODE:", 0, 5)) s = s.substring(5).trim();
        return s.trim();
    }

    private void selectSiteFromInput(String rawInput) {
        String raw = normalizeScannedValue(rawInput);
        if (raw.isEmpty()) {
            Toast.makeText(this, "Invalid Project Code.", Toast.LENGTH_SHORT).show();
            return;
        }
        String projectId = null;
        try { projectId = projectRepo.resolveProjectId(raw); } catch (Exception ignored) {}
        boolean foundLocal = projectId != null && !projectId.trim().isEmpty();
        if (pickOnly && !foundLocal) {
            Toast.makeText(this, "Project Code not found in the synced Projects list. Refresh Projects and try again.", Toast.LENGTH_LONG).show();
            return;
        }

        String finalSiteId = foundLocal ? projectId.trim() : extractLeadingReference(raw);
        String projectType = foundLocal ? projectRepo.getProjectTypeById(finalSiteId) : CameraPrefs.DOC_INFRA;

        if (pickOnly) {
            if (!requiredProjectType.isEmpty() && !requiredProjectType.equalsIgnoreCase(projectType)) {
                String requiredLabel = CameraPrefs.DOC_INFRA.equals(requiredProjectType) ? "Infrastructure" : requiredProjectType.replace('_', ' ');
                Toast.makeText(this, "Select an " + requiredLabel + " project for these photos.", Toast.LENGTH_LONG).show();
                return;
            }
            String code = projectRepo.getProjectCodeById(finalSiteId);
            Intent result = new Intent();
            result.putExtra(EXTRA_SITE_ID, finalSiteId);
            result.putExtra(EXTRA_UNCATEGORIZED, false);
            result.putExtra(EXTRA_SELECTED_PROJECT_TYPE, projectType);
            result.putExtra(EXTRA_SELECTED_PROJECT_CODE, code == null || code.trim().isEmpty() ? raw : code.trim());
            setResult(RESULT_OK, result);
            finish();
            return;
        }

        if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(projectType)) {
            // Keep the existing dedicated Project Activity compatibility flow.
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);
            cameraPrefs.saveActivityProjectId(finalSiteId);
            captureContextRepo.setCurrent(CameraPrefs.DOC_PROJECT_ACTIVITY, finalSiteId);
        } else if (CameraPrefs.DOC_ACTIVITY.equals(projectType)) {
            // Standalone Activity uses its unified tbl_project UUID as siteId.
            // UploadWorker therefore uses /api/geocamera/upload and lets the API
            // determine ACTIVITY from vw_geoklik_document_targets.
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_ACTIVITY);
            cameraPrefs.clearActivityProjectId();
            captureContextRepo.setCurrent(CameraPrefs.DOC_ACTIVITY, null);
        } else {
            // Proven Infrastructure behavior remains unchanged.
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_INFRA);
            cameraPrefs.clearActivityProjectId();
            captureContextRepo.setCurrent(CameraPrefs.DOC_INFRA, null);
        }

        cameraPrefs.saveSite(finalSiteId, false);
        if (foundLocal) {
            String label = projectRepo.getProjectDisplayLabel(finalSiteId);
            String typeLabel;
            if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(projectType)) {
                typeLabel = "Project Activity";
            } else if (CameraPrefs.DOC_ACTIVITY.equals(projectType)) {
                typeLabel = "Activity";
            } else {
                typeLabel = "Infrastructure";
            }
            Toast.makeText(this, (label == null || label.trim().isEmpty() ? "Project verified" : label) + "\n" + typeLabel, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Code saved for offline use. Server verification will still apply during sync.", Toast.LENGTH_LONG).show();
        }
        finishWithResult(finalSiteId, false);
    }

    private String extractLeadingReference(String value) {
        String s = value == null ? "" : value.trim();
        int idx = s.indexOf(" — ");
        if (idx < 0) idx = s.indexOf(" - ");
        if (idx > 0) s = s.substring(0, idx).trim();
        return s;
    }

    private static String normalizeProjectType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.US);
        if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type)) return CameraPrefs.DOC_PROJECT_ACTIVITY;
        if (CameraPrefs.DOC_ACTIVITY.equals(type)) return CameraPrefs.DOC_ACTIVITY;
        if (CameraPrefs.DOC_PERSONAL.equals(type)) return CameraPrefs.DOC_PERSONAL;
        if (CameraPrefs.DOC_INFRA.equals(type)) return CameraPrefs.DOC_INFRA;
        return type;
    }

    private void showPersonalCaptureDialog() {
        int padding = dp(20);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, dp(8), padding, 0);

        TextInputLayout tilLabel = new TextInputLayout(this);
        tilLabel.setHint("Overlay Label");
        tilLabel.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText etLabel = new TextInputEditText(this);
        etLabel.setSingleLine(true);
        etLabel.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etLabel.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
        etLabel.setText(cameraPrefs.getPersonalOverlayLabel());
        etLabel.setSelection(etLabel.getText() == null ? 0 : etLabel.getText().length());
        tilLabel.addView(etLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextInputLayout tilTitle = new TextInputLayout(this);
        tilTitle.setHint("Overlay Title");
        tilTitle.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText etTitle = new TextInputEditText(this);
        etTitle.setSingleLine(true);
        etTitle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        etTitle.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        tilTitle.addView(etTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(12);
        container.addView(tilLabel);
        container.addView(tilTitle, titleLp);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Personal Capture")
                .setMessage("Set the overlay label and a title for this photo session.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = etLabel.getText() == null ? "" : etLabel.getText().toString().trim();
            String title = etTitle.getText() == null ? "" : etTitle.getText().toString().trim();
            if (label.isEmpty()) {
                tilLabel.setError("Overlay label is required.");
                return;
            }
            if (title.isEmpty()) {
                tilTitle.setError("Title is required.");
                return;
            }
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PERSONAL);
            cameraPrefs.savePersonalOverlay(label, title);
            cameraPrefs.saveActivityProjectId("");
            captureContextRepo.setCurrent(CameraPrefs.DOC_PERSONAL, null);
            cameraPrefs.saveSite("", true);
            dialog.dismiss();
            finishWithResult("", true, title, CameraPrefs.DOC_PERSONAL, label);
        }));
        dialog.show();
    }

    private String decodeQrFromImage(Uri uri) throws Exception {
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in);
        }
        if (bitmap == null) return null;
        try {
            int[] rotations = new int[]{0, 90, 180, 270};
            for (int degrees : rotations) {
                Bitmap candidate = degrees == 0 ? bitmap : rotateBitmap(bitmap, degrees);
                try {
                    String decoded = decodeQrBitmap(candidate);
                    if (decoded != null && !decoded.trim().isEmpty()) return decoded;
                } finally {
                    if (candidate != bitmap && !candidate.isRecycled()) candidate.recycle();
                }
            }
            return null;
        } finally {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private String decodeQrBitmap(Bitmap bitmap) throws Exception {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return null;
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        Result result = new MultiFormatReader().decode(binaryBitmap, hints);
        return result == null ? null : result.getText();
    }

    private Bitmap rotateBitmap(Bitmap source, int degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private void finishWithResult(String siteId, boolean uncategorized) {
        finishWithResult(siteId, uncategorized, null, null, null);
    }

    private void finishWithResult(String siteId, boolean uncategorized, String sessionTitle, String documentationType, String personalOverlayLabel) {
        Intent result = new Intent();
        result.putExtra(EXTRA_SITE_ID, siteId);
        result.putExtra(EXTRA_UNCATEGORIZED, uncategorized);
        if (sessionTitle != null) result.putExtra("EXTRA_SESSION_TITLE", sessionTitle);
        if (documentationType != null) result.putExtra("EXTRA_DOCUMENTATION_TYPE", documentationType);
        if (personalOverlayLabel != null) result.putExtra("EXTRA_PERSONAL_OVERLAY_LABEL", personalOverlayLabel);
        setResult(RESULT_OK, result);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
