package ph.gov.geocamera.presentation.site;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.ProjectRepository;

public class SetSiteActivity extends AppCompatActivity {

    public static final String EXTRA_SITE_ID = "EXTRA_SITE_ID";
    public static final String EXTRA_UNCATEGORIZED = "EXTRA_UNCATEGORIZED";

    private ProjectRepository projectRepo;
    private CameraPrefs cameraPrefs;

    private ActivityResultLauncher<ScanOptions> qrLauncher;

    // Keep same XML id/layout: actSite
    // But no adapter, no suggestions, no dropdown.
    private MaterialAutoCompleteTextView actSite;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_set_site);

        projectRepo = new ProjectRepository(this);
        cameraPrefs = new CameraPrefs(this);

        actSite = findViewById(R.id.actSite);

        MaterialButton btnUseSelected = findViewById(R.id.btnUseSelected);
        MaterialButton btnScanQr = findViewById(R.id.btnScanQr);
        MaterialButton btnUncategorized = findViewById(R.id.btnUncategorized);
        MaterialButton btnClose = findViewById(R.id.btnClose);

        setupManualInputOnly();

        btnUseSelected.setOnClickListener(v -> {
            hideKeyboard();
            actSite.clearFocus();
            actSite.dismissDropDown();

            String raw = actSite.getText() == null ? "" : actSite.getText().toString().trim();
            raw = normalizeScannedValue(raw);

            if (raw.isEmpty()) {
                Toast.makeText(this, "Please type or scan a Project ID.", Toast.LENGTH_SHORT).show();
                return;
            }

            selectSiteFromInput(raw);
        });

        qrLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() == null) return;

            String scanned = normalizeScannedValue(result.getContents());

            if (scanned.isEmpty()) {
                Toast.makeText(this, "Invalid QR content.", Toast.LENGTH_SHORT).show();
                return;
            }

            actSite.setText(scanned, false);
            actSite.setSelection(scanned.length());
            actSite.dismissDropDown();
            hideKeyboard();
            actSite.clearFocus();

            Toast.makeText(this, "Scanned: " + scanned, Toast.LENGTH_SHORT).show();

            // Auto resolve after scan
            handler.postDelayed(() -> selectSiteFromInput(scanned), 120);
        });

        btnScanQr.setOnClickListener(v -> startQrScan());
        btnUncategorized.setOnClickListener(v -> selectUncategorized());

        btnClose.setOnClickListener(v -> {
            Intent i = new Intent(SetSiteActivity.this, ph.gov.geocamera.presentation.home.HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        });
    }

    private void setupManualInputOnly() {
        if (actSite == null) return;

        // No dropdown / suggestions
        actSite.setAdapter(null);
        actSite.setThreshold(Integer.MAX_VALUE);
        actSite.dismissDropDown();

        actSite.setOnClickListener(v -> {
            // Do nothing. User can type only.
            actSite.dismissDropDown();
        });

        actSite.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                actSite.dismissDropDown();
            }
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

            hideKeyboard();
            actSite.dismissDropDown();
            actSite.clearFocus();

            String raw = actSite.getText() == null ? "" : actSite.getText().toString();
            raw = normalizeScannedValue(raw);

            if (!raw.isEmpty()) {
                selectSiteFromInput(raw);
            }

            return true;
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (getCurrentFocus() != null) {
                hideKeyboard();
                getCurrentFocus().clearFocus();
            }

            if (actSite != null) {
                actSite.dismissDropDown();
            }
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

        s = s.replace("\n", " ")
                .replace("\r", " ")
                .trim();

        while (s.contains("  ")) {
            s = s.replace("  ", " ");
        }

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
            Toast.makeText(this, "Invalid project/site.", Toast.LENGTH_SHORT).show();
            return;
        }

        String projectId = null;

        // Offline-first:
        // Try local resolution only. Do NOT block capture if it is not found locally.
        // Server-side existence check happens during UploadWorker/API sync.
        try {
            if (projectRepo.existsProjectId(raw)) {
                projectId = raw.trim();
            }

            if (projectId == null || projectId.trim().isEmpty()) {
                projectId = projectRepo.resolveProjectId(raw);
            }
        } catch (Exception ignored) {
            projectId = null;
        }

        boolean foundLocal = projectId != null && !projectId.trim().isEmpty();
        String finalSiteId = foundLocal ? projectId.trim() : raw.trim();

        cameraPrefs.saveSite(finalSiteId, false);

        if (foundLocal) {
            String label = projectRepo.getProjectDisplayLabel(finalSiteId);

            if (label == null || label.trim().isEmpty()) {
                Toast.makeText(this, "Selected project: " + finalSiteId, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Selected project: " + label, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(
                    this,
                    "Offline site selected. It will be verified during sync.",
                    Toast.LENGTH_LONG
            ).show();
        }

        finishWithResult(finalSiteId, false);
    }

    private void selectUncategorized() {
        cameraPrefs.saveSite(null, true);
        finishWithResult(null, true);
    }

    private void finishWithResult(String projectId, boolean uncategorized) {
        Intent result = new Intent();
        result.putExtra(EXTRA_SITE_ID, projectId);
        result.putExtra(EXTRA_UNCATEGORIZED, uncategorized);
        setResult(RESULT_OK, result);
        finish();
    }
}
