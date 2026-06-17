package ph.gov.geocamera.presentation.site;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.core.utils.SimpleTextWatcher;
import ph.gov.geocamera.data.repository.ProjectRepository;

public class SetSiteActivity extends AppCompatActivity {

    public static final String EXTRA_SITE_ID = "EXTRA_SITE_ID";
    public static final String EXTRA_UNCATEGORIZED = "EXTRA_UNCATEGORIZED";

    private static final int SUGGEST_LIMIT = 50;

    private ProjectRepository projectRepo;
    private CameraPrefs cameraPrefs;

    private ActivityResultLauncher<ScanOptions> qrLauncher;

    private MaterialAutoCompleteTextView actSite;
    private ArrayAdapter<String> siteAdapter;

    private boolean isSelecting = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch = null;

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

        List<String> initial = safeList(projectRepo.getProjectSuggestions("", SUGGEST_LIMIT));
        siteAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                new ArrayList<>(initial)
        );

        actSite.setAdapter(siteAdapter);
        actSite.setThreshold(0);

        actSite.setOnClickListener(v -> actSite.showDropDown());

        actSite.setOnItemClickListener((parent, view, position, id) -> {
            isSelecting = true;
            Object item = parent.getItemAtPosition(position);
            String chosen = item == null ? "" : item.toString();
            actSite.setText(chosen, false);
            actSite.setSelection(chosen.length());
            handler.postDelayed(() -> isSelecting = false, 250);
        });

        actSite.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isSelecting) return;

                final String q = s == null ? "" : s.toString();

                if (pendingSearch != null) {
                    handler.removeCallbacks(pendingSearch);
                }

                pendingSearch = () -> {
                    if (isSelecting) return;

                    List<String> matches = safeList(projectRepo.getProjectSuggestions(q, SUGGEST_LIMIT));

                    siteAdapter.clear();
                    siteAdapter.addAll(matches);
                    siteAdapter.notifyDataSetChanged();

                    if (!actSite.isPopupShowing()) {
                        actSite.showDropDown();
                    }
                };

                handler.postDelayed(pendingSearch, 220);
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

        btnUseSelected.setOnClickListener(v -> {
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

            isSelecting = true;
            actSite.setText(scanned, false);
            actSite.setSelection(scanned.length());
            actSite.dismissDropDown();
            hideKeyboard();
            actSite.clearFocus();

            handler.postDelayed(() -> isSelecting = false, 200);

            Toast.makeText(this, "Scanned: " + scanned, Toast.LENGTH_SHORT).show();

            // auto resolve agad after scan
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

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            } else if (imm != null) {
                imm.hideSoftInputFromWindow(actSite.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void startQrScan() {
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
        }

        return s.trim();
    }

    private void selectSiteFromInput(String rawInput) {
        String projectId = null;

        String raw = normalizeScannedValue(rawInput);
        if (raw.isEmpty()) {
            Toast.makeText(this, "Invalid project.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1) exact raw project_id muna
        if (projectRepo.existsProjectId(raw)) {
            projectId = raw;
        }

        // 2) fallback resolve via code / coda / display label
        if (projectId == null || projectId.trim().isEmpty()) {
            projectId = projectRepo.resolveProjectId(raw);
        }

        if (projectId == null || projectId.trim().isEmpty()) {
            Toast.makeText(this, "Project not found in local synced projects.", Toast.LENGTH_LONG).show();
            return;
        }

        cameraPrefs.saveSite(projectId, false);

        String label = projectRepo.getProjectDisplayLabel(projectId);
        if (label == null || label.trim().isEmpty()) {
            Toast.makeText(this, "Selected project: " + projectId, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Selected project: " + label, Toast.LENGTH_SHORT).show();
        }

        finishWithResult(projectId, false);
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

    private List<String> safeList(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }
}