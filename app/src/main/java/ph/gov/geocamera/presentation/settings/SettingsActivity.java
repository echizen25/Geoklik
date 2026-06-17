package ph.gov.geocamera.presentation.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.ArrayList;
import java.util.List;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.core.utils.SimpleTextWatcher;
import ph.gov.geocamera.data.repository.SiteRepository;
import ph.gov.geocamera.presentation.common.BaseTopAppBarActivity;
import ph.gov.geocamera.presentation.geocamera.GeoCameraActivity;
import ph.gov.geocamera.presentation.home.HomeActivity;

public class SettingsActivity extends BaseTopAppBarActivity {

    private static final int SUGGEST_LIMIT = 50;

    private SiteRepository siteRepo;
    private CameraPrefs cameraPrefs;

    private TextView tvCurrentSelection;
    private TextView tvLatestDb;

    private MaterialAutoCompleteTextView actSite;
    private ArrayAdapter<String> siteAdapter;

    private boolean isSelecting = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    private ActivityResultLauncher<ScanOptions> qrLauncher;

    @Override
    protected int getLayoutResId() {
        return R.layout.activity_settings;
    }

    @Override
    protected String getScreenTitle() {
        return "Settings";
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        siteRepo = new SiteRepository(this);
        cameraPrefs = new CameraPrefs(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());

            toolbar.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();

                if (id == R.id.action_geocam) {
                    startActivity(new Intent(this, GeoCameraActivity.class));
                    return true;
                }

                if (id == R.id.action_home) {
                    Intent i = new Intent(this, HomeActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                    return true;
                }

                return false;
            });
        }

        tvCurrentSelection = findViewById(R.id.tvCurrentSelection);
        tvLatestDb = findViewById(R.id.tvLatestDb);
        actSite = findViewById(R.id.actSite);

        MaterialButton btnUseSelected = findViewById(R.id.btnUseSelected);
        MaterialButton btnScanQr = findViewById(R.id.btnScanQr);
        MaterialButton btnUncategorized = findViewById(R.id.btnUncategorized);

        setupDropdown();
        setupQrLauncher();

        if (btnUseSelected != null) {
            btnUseSelected.setOnClickListener(v -> {
                String siteId = getTypedSiteId();
                if (siteId.isEmpty()) {
                    Toast.makeText(this, "Please select a Site ID.", Toast.LENGTH_SHORT).show();
                    return;
                }
                selectSite(siteId);
            });
        }

        if (btnScanQr != null) {
            btnScanQr.setOnClickListener(v -> startQrScan());
        }

        if (btnUncategorized != null) {
            btnUncategorized.setOnClickListener(v -> selectUncategorized());
        }

        refreshDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDisplay();
    }

    private void setupDropdown() {
        if (actSite == null) return;

        List<String> initial = safeList(siteRepo.getAllSiteIds(SUGGEST_LIMIT));

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

            handler.postDelayed(() -> isSelecting = false, 200);
        });

        actSite.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isSelecting) return;

                String q = s == null ? "" : s.toString();

                if (pendingSearch != null) {
                    handler.removeCallbacks(pendingSearch);
                }

                pendingSearch = () -> {
                    List<String> matches = q.trim().isEmpty()
                            ? safeList(siteRepo.getAllSiteIds(SUGGEST_LIMIT))
                            : safeList(siteRepo.searchSiteIds(q, SUGGEST_LIMIT));

                    siteAdapter.clear();
                    siteAdapter.addAll(matches);
                    siteAdapter.notifyDataSetChanged();

                    if (!actSite.isPopupShowing()) {
                        actSite.showDropDown();
                    }
                };

                handler.postDelayed(pendingSearch, 250);
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
            return true;
        });
    }

    private void setupQrLauncher() {
        qrLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() == null) return;

            String scanned = result.getContents().trim();

            if (scanned.regionMatches(true, 0, "SITE:", 0, 5)) {
                scanned = scanned.substring(5).trim();
            }

            if (scanned.isEmpty()) return;

            isSelecting = true;
            actSite.setText(scanned, false);
            actSite.setSelection(scanned.length());
            actSite.dismissDropDown();
            hideKeyboard();
            actSite.clearFocus();

            handler.postDelayed(() -> isSelecting = false, 200);

            Toast.makeText(this, "Scanned: " + scanned, Toast.LENGTH_SHORT).show();
        });
    }

    private void startQrScan() {
        if (qrLauncher == null) return;

        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan Site QR");
        options.setBeepEnabled(true);
        options.setOrientationLocked(false);
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);

        qrLauncher.launch(options);
    }

    private void selectSite(String siteId) {
        siteRepo.getOrCreateSite(siteId, null, null, null);
        cameraPrefs.saveSite(siteId, false);

        refreshDisplay();

        Toast.makeText(this, "Site set: " + siteId, Toast.LENGTH_SHORT).show();
    }

    private void selectUncategorized() {
        cameraPrefs.saveSite(null, true);

        refreshDisplay();

        Toast.makeText(this, "UNCATEGORIZED selected", Toast.LENGTH_SHORT).show();
    }

    private void refreshDisplay() {
        if (tvCurrentSelection != null) {
            if (cameraPrefs.hasSelection()) {
                if (cameraPrefs.isUncategorized()) {
                    tvCurrentSelection.setText("UNCATEGORIZED");
                } else {
                    String siteId = cameraPrefs.getSiteId();
                    tvCurrentSelection.setText(
                            siteId == null || siteId.trim().isEmpty() ? "-" : siteId
                    );
                }
            } else {
                tvCurrentSelection.setText("-");
            }
        }

        if (tvLatestDb != null) {
            String latest = siteRepo.getLatestSiteId();
            tvLatestDb.setText("Latest in DB: " + (latest == null ? "-" : latest));
        }
    }

    private String getTypedSiteId() {
        if (actSite == null || actSite.getText() == null) return "";
        return actSite.getText().toString().trim();
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

    private List<String> safeList(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }
}