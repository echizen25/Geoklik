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
import android.text.InputFilter;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.CaptureContextRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.data.sync.ProjectBackgroundSync;

public class SetSiteActivity extends AppCompatActivity {

    public static final String EXTRA_SITE_ID = "EXTRA_SITE_ID";
    public static final String EXTRA_UNCATEGORIZED = "EXTRA_UNCATEGORIZED";

    // Selection-only mode lets Gallery reuse this exact Change Project module
    // without mutating the camera's current project/capture context.
    public static final String EXTRA_PICK_ONLY = "EXTRA_PICK_ONLY";
    public static final String EXTRA_REQUIRED_PROJECT_TYPE = "EXTRA_REQUIRED_PROJECT_TYPE";
    public static final String EXTRA_SELECTED_PROJECT_TYPE = "EXTRA_SELECTED_PROJECT_TYPE";
    public static final String EXTRA_SELECTED_PROJECT_CODE = "EXTRA_SELECTED_PROJECT_CODE";

    private ProjectRepository projectRepo;
    private CaptureContextRepository captureContextRepo;
    private CameraPrefs cameraPrefs;

    private ActivityResultLauncher<ScanOptions> qrLauncher;
    private ActivityResultLauncher<String> qrImageLauncher;
    private TextInputEditText actSite;

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
            requiredProjectType = normalizeProjectType(
                    request.getStringExtra(EXTRA_REQUIRED_PROJECT_TYPE)
            );
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
                tvSubtitle.setText(
                        CameraPrefs.DOC_INFRA.equals(requiredProjectType)
                                ? "Paste a Project Code or use QR. Infrastructure projects only."
                                : "Paste a Project Code or use QR."
                );
            }
            if (cardPersonalCapture != null) cardPersonalCapture.setVisibility(View.GONE);
            if (btnUncategorized != null) btnUncategorized.setVisibility(View.GONE);
            if (btnUseSelected != null) btnUseSelected.setText("Use Project");
        }

        setupProjectCodeInput();
        setupQrLaunchers();

        // Keep capture targets in the local cache for code/type resolution,
        // but never expose the full synced project list on this screen.
        ProjectBackgroundSync.syncIfNeeded(this, false, null);

        btnUseSelected.setOnClickListener(v -> submitCurrentProjectCode());
        btnPasteCode.setOnClickListener(v -> pasteProjectCodeFromClipboard());
        btnScanQr.setOnClickListener(v -> startQrScan());
        btnUploadQr.setOnClickListener(v -> qrImageLauncher.launch("image/*"));
        btnUncategorized.setOnClickListener(v -> {
            if (!pickOnly) showPersonalCaptureDialog();
        });

        btnClose.setOnClickListener(v -> {
            if (pickOnly) {
                setResult(RESULT_CANCELED);
                finish();
                return;
            }

            Intent i = new Intent(SetSiteActivity.this,
                    ph.gov.geocamera.presentation.home.HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        });
    }

    private void setupProjectCodeInput() {
        if (actSite == null) return;

        actSite.setSingleLine(true);
        actSite.setImeOptions(EditorInfo.IME_ACTION_DONE);
        actSite.setOnEditorActionListener((v, actionId, event) -> {
            boolean isDone =
                    actionId == EditorInfo.IME_ACTION_DONE
                            || actionId == EditorInfo.IME_ACTION_SEARCH
                            || actionId == EditorInfo.IME_ACTION_GO
                            || actionId == EditorInfo.IME_ACTION_NEXT
                            || (event != null
                            && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);

            if (!isDone) return false;
            submitCurrentProjectCode();
            return true;
        });
    }

    private void pasteProjectCodeFromClipboard() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

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

        actSite.setText(pasted);
        actSite.setSelection(pasted.length());
        actSite.requestFocus();
        Toast.makeText(this, "Project code pasted", Toast.LENGTH_SHORT).show();
    }

    private void setupQrLaunchers() {
        qrLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() == null) return;
            applyQrValue(result.getContents(), "QR scanned");
        });

        qrImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;

                    Toast.makeText(this, "Reading QR image…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String value = null;
                        try {
                            value = decodeQrFromImage(uri);
                        } catch (Exception ignored) {
                        }

                        final String decoded = value;
                        runOnUiThread(() -> {
                            if (decoded == null || decoded.trim().isEmpty()) {
                                Toast.makeText(
                                        this,
                                        "No readable QR code found in that image.",
                                        Toast.LENGTH_LONG
                                ).show();
                                return;
                            }
                            applyQrValue(decoded, "QR image read");
                        });
                    }).start();
                }
        );
    }

    private void applyQrValue(String value, String message) {
        String scanned = normalizeScannedValue(value);
        if (scanned.isEmpty()) {
            Toast.makeText(this, "Invalid QR content.", Toast.LENGTH_SHORT).show();
            return;
        }

        actSite.setText(scanned);
        actSite.setSelection(scanned.length());
        hideKeyboard();
        actSite.clearFocus();

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> selectSiteFromInput(scanned), 120);
    }

    private void submitCurrentProjectCode() {
        hideKeyboard();
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
        if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (getCurrentFocus() != null) {
                hideKeyboard();
                getCurrentFocus().clearFocus();
            }
        }

        return super.dispatchTouchEvent(ev);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            } else if (imm != null && actSite != null) {
                imm.hideSoftInputFromWindow(actSite.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void startQrScan() {
        hideKeyboard();

        if (actSite != null) actSite.clearFocus();

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

        if (s.regionMatches(true, 0, "SITE:", 0, 5)) {
            s = s.substring(5).trim();
        } else if (s.regionMatches(true, 0, "PROJECT:", 0, 8)) {
            s = s.substring(8).trim();
        } else if (s.regionMatches(true, 0, "CODE:", 0, 5)) {
            s = s.substring(5).trim();
        }

        return s.trim();
    }

    private void selectSiteFromInput(String rawInput) {
        String raw = normalizeScannedValue(rawInput);
        if (raw.isEmpty()) {
            Toast.makeText(this, "Invalid Project Code.", Toast.LENGTH_SHORT).show();
            return;
        }

        String projectId = null;
        try {
            projectId = projectRepo.resolveProjectId(raw);
        } catch (Exception ignored) {
        }

        boolean foundLocal = projectId != null && !projectId.trim().isEmpty();

        if (pickOnly && !foundLocal) {
            Toast.makeText(
                    this,
                    "Project Code not found in the synced Projects list. Refresh Projects and try again.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        String finalSiteId = foundLocal ? projectId.trim() : extractLeadingReference(raw);

        // A known target determines the capture classification automatically.
        // Unknown/offline legacy codes default to INFRA to preserve the existing upload workflow.
        String projectType = foundLocal
                ? projectRepo.getProjectTypeById(finalSiteId)
                : CameraPrefs.DOC_INFRA;

        if (pickOnly) {
            if (!requiredProjectType.isEmpty()
                    && !requiredProjectType.equalsIgnoreCase(projectType)) {
                String requiredLabel = CameraPrefs.DOC_INFRA.equals(requiredProjectType)
                        ? "Infrastructure"
                        : requiredProjectType.replace('_', ' ');
                Toast.makeText(
                        this,
                        "Select an " + requiredLabel + " project for these photos.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            String code = projectRepo.getProjectCodeById(finalSiteId);
            Intent result = new Intent();
            result.putExtra(EXTRA_SITE_ID, finalSiteId);
            result.putExtra(EXTRA_UNCATEGORIZED, false);
            result.putExtra(EXTRA_SELECTED_PROJECT_TYPE, projectType);
            result.putExtra(EXTRA_SELECTED_PROJECT_CODE,
                    code == null || code.trim().isEmpty() ? raw : code.trim());
            setResult(RESULT_OK, result);
            finish();
            return;
        }

        if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(projectType)) {
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);
            cameraPrefs.saveActivityProjectId(finalSiteId);
            captureContextRepo.setCurrent(CameraPrefs.DOC_PROJECT_ACTIVITY, finalSiteId);
        } else {
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_INFRA);
            cameraPrefs.clearActivityProjectId();
            captureContextRepo.setCurrent(CameraPrefs.DOC_INFRA, null);
        }

        cameraPrefs.saveSite(finalSiteId, false);

        if (foundLocal) {
            String label = projectRepo.getProjectDisplayLabel(finalSiteId);
            String typeLabel = CameraPrefs.DOC_PROJECT_ACTIVITY.equals(projectType)
                    ? "Project Activity"
                    : "Infrastructure";

            Toast.makeText(
                    this,
                    (label == null || label.trim().isEmpty() ? "Project verified" : label)
                            + "\n" + typeLabel,
                    Toast.LENGTH_SHORT
            ).show();
        } else {
            Toast.makeText(
                    this,
                    "Code saved for offline use. Server verification will still apply during sync.",
                    Toast.LENGTH_LONG
            ).show();
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
        tilLabel.addView(etLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextInputLayout tilTitle = new TextInputLayout(this);
        tilTitle.setHint("Overlay Title");
        tilTitle.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(12);
        tilTitle.setLayoutParams(titleParams);

        TextInputEditText etTitle = new TextInputEditText(this);
        etTitle.setSingleLine(true);
        etTitle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        etTitle.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        etTitle.setText(cameraPrefs.getPersonalOverlayTitle());
        tilTitle.addView(etTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        container.addView(tilLabel);
        container.addView(tilTitle);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Personal Capture")
                .setMessage("Customize the existing first watermark line. These settings apply only to Personal photos.")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use Personal", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String label = cleanPersonalOverlayText(
                            etLabel.getText() == null ? "" : etLabel.getText().toString(),
                            "PERSONAL",
                            30
                    );
                    String title = cleanPersonalOverlayText(
                            etTitle.getText() == null ? "" : etTitle.getText().toString(),
                            "Personal Capture",
                            60
                    );

                    cameraPrefs.savePersonalOverlay(label, title);
                    cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PERSONAL);
                    cameraPrefs.clearActivityProjectId();
                    captureContextRepo.setCurrent(CameraPrefs.DOC_PERSONAL, null);

                    // Keep a readable local target so the existing watermark can
                    // render the custom title without changing the INFRA/Activity
                    // watermark implementation. PERSONAL remains local-only because
                    // monitoring_type, not siteId, controls synchronization.
                    cameraPrefs.saveSite(title, false);

                    dialog.dismiss();
                    Toast.makeText(
                            this,
                            label + " | " + title + "\nOn device only",
                            Toast.LENGTH_SHORT
                    ).show();
                    finishWithResult(title, false);
                }));

        dialog.show();
    }

    private String cleanPersonalOverlayText(String value, String fallback, int maxLength) {
        String out = value == null ? "" : value.trim();
        out = out.replace("\n", " ").replace("\r", " ");
        out = out.replaceAll("[\\\\/:*?\"<>|]", "-");
        while (out.contains("  ")) out = out.replace("  ", " ");
        while (out.contains("--")) out = out.replace("--", "-");
        out = out.trim();
        if (out.isEmpty()) out = fallback;
        if (out.length() > maxLength) out = out.substring(0, maxLength).trim();
        return out;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String decodeQrFromImage(Uri uri) throws Exception {
        Bitmap source = decodeScaledBitmap(uri, 2200);
        if (source == null) return null;

        try {
            int[] rotations = new int[]{0, 90, 180, 270};
            for (int degrees : rotations) {
                Bitmap candidate = source;
                if (degrees != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(degrees);
                    candidate = Bitmap.createBitmap(
                            source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
                }

                try {
                    String result = decodeQrBitmap(candidate);
                    if (result != null && !result.trim().isEmpty()) return result.trim();
                } catch (Exception ignored) {
                } finally {
                    if (candidate != source && !candidate.isRecycled()) candidate.recycle();
                }
            }
            return null;
        } finally {
            if (!source.isRecycled()) source.recycle();
        }
    }

    private Bitmap decodeScaledBitmap(Uri uri, int maxDimension) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;

        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }

        int sample = 1;
        while (bounds.outWidth / sample > maxDimension
                || bounds.outHeight / sample > maxDimension) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        try (InputStream input = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private String decodeQrBitmap(Bitmap bitmap) throws Exception {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        MultiFormatReader reader = new MultiFormatReader();
        Result result = reader.decode(binaryBitmap, hints);
        return result == null ? null : result.getText();
    }

    private void finishWithResult(String projectId, boolean uncategorized) {
        Intent result = new Intent();
        result.putExtra(EXTRA_SITE_ID, projectId);
        result.putExtra(EXTRA_UNCATEGORIZED, uncategorized);
        setResult(RESULT_OK, result);
        finish();
    }
}
