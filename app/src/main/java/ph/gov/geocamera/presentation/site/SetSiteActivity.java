package ph.gov.geocamera.presentation.site;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
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
import java.util.List;
import java.util.Map;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.CaptureContextRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.data.sync.ProjectBackgroundSync;

public class SetSiteActivity extends AppCompatActivity {

    public static final String EXTRA_SITE_ID = "EXTRA_SITE_ID";
    public static final String EXTRA_UNCATEGORIZED = "EXTRA_UNCATEGORIZED";

    private ProjectRepository projectRepo;
    private CaptureContextRepository captureContextRepo;
    private CameraPrefs cameraPrefs;

    private ActivityResultLauncher<ScanOptions> qrLauncher;
    private ActivityResultLauncher<String> qrImageLauncher;
    private MaterialAutoCompleteTextView actSite;
    private ArrayAdapter<String> localProjectsAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_set_site);

        projectRepo = new ProjectRepository(this);
        captureContextRepo = new CaptureContextRepository(this);
        cameraPrefs = new CameraPrefs(this);

        actSite = findViewById(R.id.actSite);

        MaterialButton btnUseSelected = findViewById(R.id.btnUseSelected);
        MaterialButton btnScanQr = findViewById(R.id.btnScanQr);
        MaterialButton btnUploadQr = findViewById(R.id.btnUploadQr);
        MaterialButton btnUncategorized = findViewById(R.id.btnUncategorized);
        MaterialButton btnClose = findViewById(R.id.btnClose);

        setupProjectSelector();
        setupQrLaunchers();

        // The picker is an explicit user action, so refresh the capture target list now.
        // If offline, the existing local list remains fully usable.
        ProjectBackgroundSync.syncIfNeeded(this, true, updated ->
                runOnUiThread(this::refreshProjectSuggestions));

        btnUseSelected.setOnClickListener(v -> submitCurrentProjectCode());
        btnScanQr.setOnClickListener(v -> startQrScan());
        btnUploadQr.setOnClickListener(v -> qrImageLauncher.launch("image/*"));
        btnUncategorized.setOnClickListener(v -> selectPersonalCapture());

        btnClose.setOnClickListener(v -> {
            Intent i = new Intent(SetSiteActivity.this,
                    ph.gov.geocamera.presentation.home.HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        });
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

        actSite.setText(scanned, false);
        actSite.setSelection(scanned.length());
        actSite.dismissDropDown();
        hideKeyboard();
        actSite.clearFocus();

        Toast.makeText(this, message + ": " + scanned, Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> selectSiteFromInput(scanned), 120);
    }

    private void setupProjectSelector() {
        if (actSite == null) return;

        localProjectsAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                projectRepo.getProjectSuggestions("", 300)
        );

        actSite.setAdapter(localProjectsAdapter);
        actSite.setThreshold(0);

        actSite.setOnClickListener(v -> {
            if (localProjectsAdapter != null && localProjectsAdapter.getCount() > 0) {
                actSite.showDropDown();
            }
        });

        actSite.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && localProjectsAdapter != null && localProjectsAdapter.getCount() > 0) {
                actSite.showDropDown();
            }
        });

        actSite.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (item == null) return;

            String selected = normalizeScannedValue(String.valueOf(item));
            if (selected.isEmpty()) return;

            hideKeyboard();
            actSite.clearFocus();
            actSite.dismissDropDown();
            selectSiteFromInput(selected);
        });

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

    private void refreshProjectSuggestions() {
        if (localProjectsAdapter == null) return;
        List<String> items = projectRepo.getProjectSuggestions("", 300);
        localProjectsAdapter.clear();
        localProjectsAdapter.addAll(items);
        localProjectsAdapter.notifyDataSetChanged();
    }

    private void submitCurrentProjectCode() {
        hideKeyboard();
        actSite.clearFocus();
        actSite.dismissDropDown();

        String raw = actSite.getText() == null ? "" : actSite.getText().toString();
        raw = normalizeScannedValue(raw);

        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter, select, or scan a Project Code.", Toast.LENGTH_SHORT).show();
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

            if (actSite != null) actSite.dismissDropDown();
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
        String finalSiteId = foundLocal ? projectId.trim() : extractLeadingReference(raw);

        // A known target determines the capture classification automatically.
        // Unknown/offline legacy codes default to INFRA to preserve the existing upload workflow.
        String projectType = foundLocal
                ? projectRepo.getProjectTypeById(finalSiteId)
                : CameraPrefs.DOC_INFRA;

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
                    (label == null || label.trim().isEmpty() ? finalSiteId : label)
                            + "\n" + typeLabel,
                    Toast.LENGTH_SHORT
            ).show();
        } else {
            Toast.makeText(
                    this,
                    "Project Code is not in the synced list. Using the existing Infrastructure workflow.",
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

    private void selectPersonalCapture() {
        // Keep the proven uncategorized storage/upload behavior. Only the
        // user-facing concept is now "Personal Capture".
        cameraPrefs.saveDocumentationType(CameraPrefs.DOC_INFRA);
        cameraPrefs.clearActivityProjectId();
        captureContextRepo.setCurrent(CameraPrefs.DOC_INFRA, null);
        cameraPrefs.saveSite(null, true);

        Toast.makeText(this, "Personal Capture selected", Toast.LENGTH_SHORT).show();
        finishWithResult(null, true);
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
