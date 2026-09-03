package ph.gov.geocamera.presentation.site;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
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
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.ArrayList;
import java.util.List;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.local.db.GeoDbHelper;
import ph.gov.geocamera.data.repository.ProjectRepository;

public class SetSiteActivity extends AppCompatActivity {

    public static final String EXTRA_SITE_ID = "EXTRA_SITE_ID";
    public static final String EXTRA_UNCATEGORIZED = "EXTRA_UNCATEGORIZED";

    private ProjectRepository projectRepo;
    private CameraPrefs cameraPrefs;
    private GeoDbHelper dbHelper;

    private ActivityResultLauncher<ScanOptions> qrLauncher;
    private MaterialAutoCompleteTextView actSite;
    private ArrayAdapter<String> localProjectsAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_set_site);

        projectRepo = new ProjectRepository(this);
        cameraPrefs = new CameraPrefs(this);
        dbHelper = new GeoDbHelper(this);

        actSite = findViewById(R.id.actSite);

        MaterialButton btnUseSelected = findViewById(R.id.btnUseSelected);
        MaterialButton btnScanQr = findViewById(R.id.btnScanQr);
        MaterialButton btnUncategorized = findViewById(R.id.btnUncategorized);
        MaterialButton btnClose = findViewById(R.id.btnClose);

        setupLocalProjectSelector();

        btnUseSelected.setOnClickListener(v -> {
            hideKeyboard();
            actSite.clearFocus();
            actSite.dismissDropDown();

            String raw = actSite.getText() == null ? "" : actSite.getText().toString().trim();
            raw = normalizeScannedValue(raw);

            if (raw.isEmpty()) {
                Toast.makeText(this, "Please select, type, or scan a Project Code.", Toast.LENGTH_SHORT).show();
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
            handler.postDelayed(() -> selectSiteFromInput(scanned), 120);
        });

        btnScanQr.setOnClickListener(v -> startQrScan());
        btnUncategorized.setOnClickListener(v -> selectMyPhotos());

        btnClose.setOnClickListener(v -> {
            Intent i = new Intent(SetSiteActivity.this, ph.gov.geocamera.presentation.home.HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        });
    }

    private void setupLocalProjectSelector() {
        if (actSite == null) return;

        List<String> localProjects = getProjectsAlreadyOnDevice();
        localProjectsAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                localProjects
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

    /**
     * Only suggests projects that already have photos in this device's local gallery.
     * This keeps the public app from exposing a global infrastructure/project directory.
     * Manual project-code entry and QR scan remain available for future server-side validation.
     */
    private List<String> getProjectsAlreadyOnDevice() {
        List<String> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;

        try {
            String sql =
                    "SELECT im.siteid, " +
                            "COALESCE(NULLIF(trim(p.coda), ''), NULLIF(trim(s.name), ''), im.siteid) AS title, " +
                            "MAX(im.timestamp) AS last_used " +
                            "FROM tbl_imagemeta im " +
                            "LEFT JOIN tbl_site s ON trim(s.siteid) = trim(im.siteid) COLLATE NOCASE " +
                            "LEFT JOIN tbl_projects p ON (" +
                            " trim(p.projectid) = trim(im.siteid) COLLATE NOCASE " +
                            " OR trim(p.code) = trim(im.siteid) COLLATE NOCASE " +
                            " OR trim(p.projectid) = trim(s.projectid) COLLATE NOCASE" +
                            ") " +
                            "WHERE im.siteid IS NOT NULL " +
                            "AND trim(im.siteid) <> '' " +
                            "AND upper(trim(im.siteid)) <> 'UNCAT' " +
                            "GROUP BY im.siteid " +
                            "ORDER BY last_used DESC " +
                            "LIMIT 50";

            c = db.rawQuery(sql, null);
            while (c.moveToNext()) {
                String projectId = c.isNull(0) ? "" : c.getString(0).trim();
                String title = c.isNull(1) ? "" : c.getString(1).trim();
                if (projectId.isEmpty()) continue;

                String label = projectId;
                if (!title.isEmpty() && !title.equalsIgnoreCase(projectId)) {
                    label = projectId + " — " + title;
                }

                if (!list.contains(label)) list.add(label);
            }
        } catch (Exception ignored) {
            // Selector still supports manual entry / QR if local gallery lookup fails.
        } finally {
            if (c != null) c.close();
            db.close();
        }

        return list;
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
            Toast.makeText(this, "Invalid project.", Toast.LENGTH_SHORT).show();
            return;
        }

        String projectId = null;

        // Offline-first: resolve locally when possible. A manually entered code can still
        // be captured offline and will be verified by the server when API support is ready.
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
        String finalSiteId = foundLocal ? projectId.trim() : extractLeadingReference(raw);

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
                    "Project selected offline. It will be verified during sync.",
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

    private void selectMyPhotos() {
        // Keep the existing uncategorized storage flag/value for backward compatibility.
        // Only the user-facing label changes to "My Photos".
        cameraPrefs.saveSite(null, true);
        Toast.makeText(this, "My Photos selected", Toast.LENGTH_SHORT).show();
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
