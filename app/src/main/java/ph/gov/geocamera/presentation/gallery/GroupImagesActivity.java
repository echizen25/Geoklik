package ph.gov.geocamera.presentation.gallery;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.export.PhotoExportManager;
import ph.gov.geocamera.data.repository.GroupRepository;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.data.sync.SyncScheduler;
import ph.gov.geocamera.presentation.map.OsmMapDialog;
import ph.gov.geocamera.presentation.map.PhotoPin;

public class GroupImagesActivity extends AppCompatActivity implements GroupImagesAdapter.Callback {

    public static final String EXTRA_GROUP_ID = "groupId";
    public static final String EXTRA_SITE_ID = "siteId";
    public static final String EXTRA_SESSION_DATE = "sessionDate";
    public static final String EXTRA_DESCRIPTION = "description";

    private com.google.android.material.appbar.MaterialToolbar toolbar;
    private RecyclerView rv;
    private View emptyState;
    private TextView tvEmptyTitle;
    private TextView tvEmptySubtitle;

    private ImageMetaRepository imageRepo;
    private GroupRepository groupRepo;
    private GroupImagesAdapter adapter;

    private final List<GroupImagesAdapter.ImageItem> allImages = new ArrayList<>();

    private String groupId;
    private String siteId;
    private String sessionDate;
    private String description;

    private int statusFilter = 0;
    private int sortMode = 0;

    private ActionMode actionMode;
    private ActivityResultLauncher<ScanOptions> changeSiteQrLauncher;
    private MaterialAutoCompleteTextView activeChangeSiteInput;

    private GridLayoutManager gridLayoutManager;
    private GridSpacingItemDecoration gridDecoration;

    private int spanCount = 3;
    private static final int MIN_SPAN = 2;
    private static final int MAX_SPAN = 6;
    private static final int REQ_WRITE_STORAGE = 3101;

    private ScaleGestureDetector scaleDetector;
    private float scaleAccumulator = 1f;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_images);

        imageRepo = new ImageMetaRepository(this);
        groupRepo = new GroupRepository(this);

        toolbar = findViewById(R.id.toolbar);
        rv = findViewById(R.id.rvImages);
        emptyState = findViewById(R.id.emptyState);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle);

        Intent i = getIntent();
        groupId = i != null ? i.getStringExtra(EXTRA_GROUP_ID) : null;
        siteId = i != null ? i.getStringExtra(EXTRA_SITE_ID) : null;
        sessionDate = i != null ? i.getStringExtra(EXTRA_SESSION_DATE) : null;
        description = i != null ? i.getStringExtra(EXTRA_DESCRIPTION) : null;

        if (groupId == null || groupId.trim().isEmpty()) {
            finish();
            return;
        }
        groupId = groupId.trim();

        String dbRemarks = safe(groupRepo.getRemarksByGroupId(groupId));
        if (!dbRemarks.isEmpty()) description = dbRemarks;

        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24);
        toolbar.setNavigationOnClickListener(v -> {
            if (actionMode != null) actionMode.finish();
            else finish();
        });
        toolbar.setTitle(!safe(description).isEmpty() ? safe(description) : "Images");
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItemClick);

        rv.setHasFixedSize(true);
        rv.setItemViewCacheSize(24);

        spanCount = clampSpan(calculateSpanCount(120));
        gridLayoutManager = new GridLayoutManager(this, spanCount);
        gridLayoutManager.setItemPrefetchEnabled(true);
        gridLayoutManager.setInitialPrefetchItemCount(spanCount * 3);
        rv.setLayoutManager(gridLayoutManager);

        gridDecoration = new GridSpacingItemDecoration(spanCount, dp(4), true);
        rv.addItemDecoration(gridDecoration);

        adapter = new GroupImagesAdapter(this, this, groupId, spanCount);
        rv.setAdapter(adapter);

        setupPinchToZoom();
        setupChangeSiteQrLauncher();
        loadImages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String dbRemarks = safe(groupRepo.getRemarksByGroupId(groupId));
        if (!dbRemarks.isEmpty()) toolbar.setTitle(dbRemarks);
        loadImages();
    }

    private boolean onToolbarMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_filter_status) {
            showStatusFilterDialog();
            return true;
        }
        if (id == R.id.action_sort_photos) {
            showSortDialog();
            return true;
        }
        return false;
    }

    private void showStatusFilterDialog() {
        String[] options = new String[]{
                "All photos", "Pending", "Synced", "Failed", "Uploading", "Saved to device"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Filter photos")
                .setSingleChoiceItems(options, statusFilter, (dialog, which) -> {
                    statusFilter = which;
                    dialog.dismiss();
                    if (actionMode != null) actionMode.finish();
                    applyFilterAndSort();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSortDialog() {
        String[] options = new String[]{
                "Newest first", "Oldest first", "Pending first", "Failed first"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Sort photos")
                .setSingleChoiceItems(options, sortMode, (dialog, which) -> {
                    sortMode = which;
                    dialog.dismiss();
                    if (actionMode != null) actionMode.finish();
                    applyFilterAndSort();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadImages() {
        allImages.clear();
        Cursor c = null;
        try {
            c = imageRepo.getImagesForGroup(groupId);
            while (c != null && c.moveToNext()) {
                GroupImagesAdapter.ImageItem it = new GroupImagesAdapter.ImageItem();
                it.uuid = c.getString(0);
                it.filename = c.getString(1);
                it.timestamp = c.getString(2);
                it.status = c.getInt(3);

                if (it.filename != null && !it.filename.trim().isEmpty()) {
                    File f = new File(it.filename);
                    if (f.exists()) {
                        it.savedToDevice = PhotoExportManager.findExistingInGallery(this, f) != null;
                    }
                }
                allImages.add(it);
            }
        } finally {
            if (c != null) c.close();
        }

        applyFilterAndSort();
        if (actionMode != null) onSelectionCountChanged(adapter.getSelectedCount());
    }

    private void applyFilterAndSort() {
        List<GroupImagesAdapter.ImageItem> visible = new ArrayList<>();

        for (GroupImagesAdapter.ImageItem it : allImages) {
            if (matchesStatusFilter(it)) visible.add(it);
        }

        Comparator<GroupImagesAdapter.ImageItem> newest =
                (a, b) -> safe(b.timestamp).compareTo(safe(a.timestamp));
        Comparator<GroupImagesAdapter.ImageItem> oldest =
                (a, b) -> safe(a.timestamp).compareTo(safe(b.timestamp));

        if (sortMode == 1) {
            visible.sort(oldest);
        } else if (sortMode == 2) {
            visible.sort(Comparator
                    .comparingInt((GroupImagesAdapter.ImageItem it) -> it.status == 0 ? 0 : 1)
                    .thenComparing(newest));
        } else if (sortMode == 3) {
            visible.sort(Comparator
                    .comparingInt((GroupImagesAdapter.ImageItem it) -> it.status == 2 ? 0 : 1)
                    .thenComparing(newest));
        } else {
            visible.sort(newest);
        }

        adapter.submit(visible);
        updateEmptyState(visible.isEmpty());
    }

    private boolean matchesStatusFilter(GroupImagesAdapter.ImageItem it) {
        if (statusFilter == 1) return it.status == 0;
        if (statusFilter == 2) return it.status == 1;
        if (statusFilter == 3) return it.status == 2;
        if (statusFilter == 4) return it.status == 3;
        if (statusFilter == 5) return it.savedToDevice;
        return true;
    }

    private void updateEmptyState(boolean empty) {
        if (rv != null) rv.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (emptyState != null) emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;

        if (allImages.isEmpty()) {
            tvEmptyTitle.setText("No photos yet");
            tvEmptySubtitle.setText("Captured photos for this inspection will appear here.");
        } else {
            tvEmptyTitle.setText("No matching photos");
            tvEmptySubtitle.setText("Try another status filter to see more photos.");
        }
    }

    @Override
    public void onImageClicked(String groupId, String clickedUuid) {
        if (adapter.isSelectionMode()) {
            adapter.toggleSelection(clickedUuid);
            return;
        }

        String title = toolbar.getTitle() != null ? toolbar.getTitle().toString() : "Photo";
        PhotoActionsBottomSheet bs = PhotoActionsBottomSheet.newInstance(groupId, clickedUuid, title);
        bs.show(getSupportFragmentManager(), "photo_actions");
    }

    public void openPreview(String groupId, String clickedUuid) {
        Intent i = new Intent(this, PreviewImagesActivity.class);
        i.putExtra(PreviewImagesActivity.EXTRA_GROUP_ID, groupId);
        i.putExtra(PreviewImagesActivity.EXTRA_UUID, clickedUuid);
        i.putExtra(PreviewImagesActivity.EXTRA_TITLE,
                toolbar.getTitle() != null ? toolbar.getTitle().toString() : "Preview");
        startActivity(i);
    }

    @Override
    public void onImageLongPressed(String uuid) {
        if (actionMode == null) actionMode = startSupportActionMode(actionModeCb);
        adapter.setSelectionMode(true);
        adapter.toggleSelection(uuid);
    }

    @Override
    public void onSelectionCountChanged(int count) {
        if (count <= 0) {
            if (actionMode != null) actionMode.finish();
            return;
        }

        if (actionMode != null) {
            actionMode.setTitle(count + " selected");
            actionMode.invalidate();
        }
    }

    public void openPreviewForUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return;
        Intent i = new Intent(this, PreviewImagesActivity.class);
        i.putExtra(PreviewImagesActivity.EXTRA_GROUP_ID, groupId);
        i.putExtra(PreviewImagesActivity.EXTRA_UUID, uuid);
        i.putExtra(PreviewImagesActivity.EXTRA_TITLE,
                toolbar.getTitle() != null ? toolbar.getTitle().toString() : "Preview");
        startActivity(i);
    }

    public void openMapForUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return;

        PhotoPin pin = imageRepo.getPhotoPinByUuid(uuid);
        if (pin == null) {
            Toast.makeText(this, "No map data for this photo.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Double.isNaN(pin.lat) || Double.isNaN(pin.lng) ||
                (Math.abs(pin.lat) < 0.000001 && Math.abs(pin.lng) < 0.000001)) {
            Toast.makeText(this, "No GPS location for this photo.", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<PhotoPin> pins = new ArrayList<>();
        pins.add(pin);
        OsmMapDialog.newInstance("Pin on Map")
                .setPins(pins)
                .show(getSupportFragmentManager(), "osm_map");
    }

    private final ActionMode.Callback actionModeCb = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.menu_group_images_selection, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            MenuItem changeSite = menu.findItem(R.id.action_change_site);
            if (changeSite != null) {
                Set<String> selected = new LinkedHashSet<>(adapter.getSelectedUuids());
                boolean hasLocked = imageRepo.hasLockedPhotosForChangeSite(selected);
                changeSite.setVisible(!hasLocked);
                changeSite.setEnabled(!hasLocked);
            }
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int id = item.getItemId();

            if (id == R.id.action_save_selected) {
                saveSelectedToDevice();
                return true;
            }
            if (id == R.id.action_share_selected) {
                shareSelectedPhotos();
                return true;
            }
            if (id == R.id.action_select_all) {
                adapter.selectAll();
                return true;
            }
            if (id == R.id.action_clear) {
                adapter.clearSelection();
                return true;
            }

            if (id == R.id.action_change_site) {
                final Set<String> selected = new LinkedHashSet<>(adapter.getSelectedUuids());
                if (selected.isEmpty()) return true;

                if (imageRepo.hasLockedPhotosForChangeSite(selected)) {
                    Toast.makeText(GroupImagesActivity.this,
                            "Only unsynced photos can be reassigned. Synced/uploading photos are locked.",
                            Toast.LENGTH_LONG).show();
                    return true;
                }

                showChangeSiteDialog(selected);
                return true;
            }

            if (id == R.id.action_delete) {
                final Set<String> selected = new LinkedHashSet<>(adapter.getSelectedUuids());
                if (selected.isEmpty()) return true;

                new AlertDialog.Builder(GroupImagesActivity.this)
                        .setTitle("Delete photos?")
                        .setMessage("Delete " + selected.size() + " selected item(s)? This cannot be undone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, w) -> deleteSelected(selected))
                        .show();
                return true;
            }

            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            adapter.clearSelection();
            adapter.setSelectionMode(false);
        }
    };

    private void saveSelectedToDevice() {
        Set<String> selected = new LinkedHashSet<>(adapter.getSelectedUuids());
        if (selected.isEmpty()) {
            Toast.makeText(this, "No photos selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
            Toast.makeText(this, "Allow storage permission, then tap Save to Device again.", Toast.LENGTH_LONG).show();
            return;
        }

        int saved = 0;
        int existing = 0;
        int missing = 0;
        int failed = 0;
        String subPath = buildExportSubPath();

        for (String uuid : selected) {
            String path = adapter.getPathByUuid(uuid);
            if (path == null || path.trim().isEmpty()) path = imageRepo.getFilenameByUuid(uuid);

            if (path == null || path.trim().isEmpty()) {
                missing++;
                continue;
            }

            File file = new File(path);
            if (!file.exists()) {
                missing++;
                continue;
            }

            try {
                PhotoExportManager.SaveResult result = PhotoExportManager.saveToDevice(this, file, subPath);
                if (result.alreadySaved) existing++;
                else saved++;
            } catch (Exception e) {
                failed++;
            }
        }

        String msg = "Saved: " + saved;
        if (existing > 0) msg += " • Already saved: " + existing;
        if (missing > 0) msg += " • Missing: " + missing;
        if (failed > 0) msg += " • Failed: " + failed;
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        loadImages();
    }

    private void shareSelectedPhotos() {
        Set<String> selected = new LinkedHashSet<>(adapter.getSelectedUuids());
        if (selected.isEmpty()) {
            Toast.makeText(this, "No photos selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<File> files = new ArrayList<>();
        for (String uuid : selected) {
            String path = adapter.getPathByUuid(uuid);
            if (path == null || path.trim().isEmpty()) path = imageRepo.getFilenameByUuid(uuid);
            if (path == null || path.trim().isEmpty()) continue;
            File f = new File(path);
            if (f.exists()) files.add(f);
        }

        if (files.isEmpty()) {
            Toast.makeText(this, "Selected photos are missing from the device.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            PhotoExportManager.sharePhotos(this, files, "Share selected GeoKlik photos");
        } catch (Exception e) {
            Toast.makeText(this, "Unable to share selected photos.", Toast.LENGTH_LONG).show();
        }
    }

    private String buildExportSubPath() {
        StringBuilder b = new StringBuilder();
        appendPath(b, siteId);
        appendPath(b, sessionDate);
        appendPath(b, description);
        return b.toString();
    }

    private void appendPath(StringBuilder b, String value) {
        String s = safe(value);
        if (s.isEmpty()) return;
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        if (s.length() > 50) s = s.substring(0, 50);
        if (b.length() > 0) b.append('/');
        b.append(s);
    }

    private void setupChangeSiteQrLauncher() {
        changeSiteQrLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result == null || result.getContents() == null) return;

            String scanned = normalizeProjectCode(result.getContents());
            if (scanned.isEmpty()) {
                Toast.makeText(this, "Invalid QR content.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (activeChangeSiteInput != null) {
                activeChangeSiteInput.setText(scanned, false);
                activeChangeSiteInput.setSelection(scanned.length());
            }
            Toast.makeText(this, "Scanned: " + scanned, Toast.LENGTH_SHORT).show();
        });
    }

    private void showChangeSiteDialog(Set<String> selectedUuids) {
        if (selectedUuids == null || selectedUuids.isEmpty()) return;

        View view = getLayoutInflater().inflate(R.layout.dialog_change_site_photos, null);
        TextInputLayout tilSite = view.findViewById(R.id.tilSite);
        MaterialAutoCompleteTextView actSite = view.findViewById(R.id.actSite);
        MaterialButton btnClose = view.findViewById(R.id.btnClose);
        MaterialButton btnUseSelected = view.findViewById(R.id.btnUseSelected);
        MaterialButton btnScanQr = view.findViewById(R.id.btnScanQr);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .create();

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnScanQr.setOnClickListener(v -> {
            activeChangeSiteInput = actSite;
            startChangeSiteQrScan();
        });

        btnUseSelected.setOnClickListener(v -> {
            String raw = actSite.getText() == null ? "" : actSite.getText().toString();
            String newSiteCode = normalizeProjectCode(raw);

            if (newSiteCode.isEmpty()) {
                tilSite.setError("Type or scan a valid project code.");
                return;
            }

            if ("UNCAT".equalsIgnoreCase(newSiteCode) || "UNCATEGORIZED".equalsIgnoreCase(newSiteCode)) {
                tilSite.setError("UNCAT is not allowed here. Scan or type a project code.");
                return;
            }

            tilSite.setError(null);
            hideKeyboard(actSite);

            if (imageRepo.hasLockedPhotosForChangeSite(selectedUuids)) {
                Toast.makeText(this,
                        "Only unsynced photos can be reassigned. Synced/uploading photos are locked.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            List<String> uuids = new ArrayList<>(selectedUuids);
            int moved = imageRepo.updateSelectedPhotosSiteId(uuids, newSiteCode);

            Toast.makeText(this,
                    "Updated " + moved + " photo(s) to " + newSiteCode,
                    Toast.LENGTH_LONG).show();

            if (actionMode != null) actionMode.finish();
            loadImages();
            SyncScheduler.enqueueUploadNow(getApplicationContext());
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> activeChangeSiteInput = null);
        dialog.show();
    }

    private void startChangeSiteQrScan() {
        if (changeSiteQrLauncher == null) {
            Toast.makeText(this, "QR scanner not ready.", Toast.LENGTH_SHORT).show();
            return;
        }

        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan Project Code / Site ID");
        options.setBeepEnabled(true);
        options.setOrientationLocked(false);
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setCameraId(0);
        changeSiteQrLauncher.launch(options);
    }

    private String normalizeProjectCode(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return "";

        s = s.replace("\n", " ").replace("\r", " ").trim();
        while (s.contains("  ")) s = s.replace("  ", " ");

        if (s.regionMatches(true, 0, "SITE:", 0, 5)) s = s.substring(5).trim();
        else if (s.regionMatches(true, 0, "PROJECT:", 0, 8)) s = s.substring(8).trim();
        else if (s.regionMatches(true, 0, "CODE:", 0, 5)) s = s.substring(5).trim();

        String[] separators = new String[]{"•", "—", "|", " - "};
        for (String sep : separators) {
            int idx = s.indexOf(sep);
            if (idx > 0) {
                s = s.substring(0, idx).trim();
                break;
            }
        }

        return s.trim().toUpperCase(java.util.Locale.US);
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    private void deleteSelected(Set<String> uuids) {
        for (String uuid : uuids) {
            String path = imageRepo.getFilenameByUuid(uuid);
            if (path != null && !path.trim().isEmpty()) {
                try { new File(path).delete(); } catch (Exception ignored) {}
            }
            imageRepo.deleteImageByUuid(uuid);
        }
        if (actionMode != null) actionMode.finish();
        loadImages();
    }

    private void setupPinchToZoom() {
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        scaleAccumulator *= detector.getScaleFactor();

                        if (scaleAccumulator > 1.15f) {
                            setSpanCount(spanCount - 1);
                            scaleAccumulator = 1f;
                        } else if (scaleAccumulator < 0.85f) {
                            setSpanCount(spanCount + 1);
                            scaleAccumulator = 1f;
                        }
                        return true;
                    }
                });

        rv.setOnTouchListener((v, event) -> {
            if (scaleDetector != null) scaleDetector.onTouchEvent(event);
            return false;
        });
    }

    private void setSpanCount(int newSpan) {
        int clamped = clampSpan(newSpan);
        if (clamped == spanCount) return;

        spanCount = clamped;
        if (gridLayoutManager != null) {
            gridLayoutManager.setSpanCount(spanCount);
            gridLayoutManager.setInitialPrefetchItemCount(spanCount * 3);
        }

        if (gridDecoration != null) rv.removeItemDecoration(gridDecoration);
        gridDecoration = new GridSpacingItemDecoration(spanCount, dp(4), true);
        rv.addItemDecoration(gridDecoration);
        rv.invalidateItemDecorations();

        if (adapter != null) adapter.updateSpanCount(spanCount);
    }

    private int clampSpan(int s) {
        return Math.max(MIN_SPAN, Math.min(MAX_SPAN, s));
    }

    private int calculateSpanCount(int itemWidthDp) {
        float dpWidth = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int count = Math.max(2, (int) (dpWidth / itemWidthDp));
        return Math.min(count, MAX_SPAN);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    public static class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {
        private final int spanCount;
        private final int spacing;
        private final boolean includeEdge;

        public GridSpacingItemDecoration(int spanCount, int spacing, boolean includeEdge) {
            this.spanCount = spanCount;
            this.spacing = spacing;
            this.includeEdge = includeEdge;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            if (position == RecyclerView.NO_POSITION) return;

            int column = position % spanCount;
            if (includeEdge) {
                outRect.left = spacing - column * spacing / spanCount;
                outRect.right = (column + 1) * spacing / spanCount;
                if (position < spanCount) outRect.top = spacing;
                outRect.bottom = spacing;
            } else {
                outRect.left = column * spacing / spanCount;
                outRect.right = spacing - (column + 1) * spacing / spanCount;
                if (position >= spanCount) outRect.top = spacing;
            }
        }
    }
}
