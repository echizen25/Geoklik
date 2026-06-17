package ph.gov.geocamera.presentation.gallery;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.repository.GroupRepository;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.presentation.map.OsmMapDialog;
import ph.gov.geocamera.presentation.map.PhotoPin;

public class GroupImagesActivity extends AppCompatActivity implements GroupImagesAdapter.Callback {

    public static final String EXTRA_GROUP_ID = "groupId";
    public static final String EXTRA_SITE_ID = "siteId";
    public static final String EXTRA_SESSION_DATE = "sessionDate";
    public static final String EXTRA_DESCRIPTION = "description";

    private com.google.android.material.appbar.MaterialToolbar toolbar;
    private RecyclerView rv;

    private ImageMetaRepository imageRepo;
    private GroupRepository groupRepo;
    private GroupImagesAdapter adapter;

    private String groupId;
    private String siteId;
    private String sessionDate;
    private String description;

    private ActionMode actionMode;

    // ✅ Grid
    private GridLayoutManager gridLayoutManager;
    private GridSpacingItemDecoration gridDecoration;

    private int spanCount = 3;
    private static final int MIN_SPAN = 2;
    private static final int MAX_SPAN = 6;

    // ✅ Pinch
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

        rv.setHasFixedSize(true);
        rv.setItemViewCacheSize(24);

        spanCount = clampSpan(calculateSpanCount(120));

        gridLayoutManager = new GridLayoutManager(this, spanCount);
        gridLayoutManager.setItemPrefetchEnabled(true);
        gridLayoutManager.setInitialPrefetchItemCount(spanCount * 3);
        rv.setLayoutManager(gridLayoutManager);

        int spacingPx = dp(4);
        rv.invalidateItemDecorations();
        gridDecoration = new GridSpacingItemDecoration(spanCount, spacingPx, true);
        rv.addItemDecoration(gridDecoration);

        adapter = new GroupImagesAdapter(this, this, groupId, spanCount);
        rv.setAdapter(adapter);

        setupPinchToZoom();
        loadImages();
    }

    @Override
    protected void onResume() {
        super.onResume();

        String dbRemarks = safe(groupRepo.getRemarksByGroupId(groupId));
        if (!dbRemarks.isEmpty()) toolbar.setTitle(dbRemarks);

        loadImages();
    }

    private void loadImages() {
        List<GroupImagesAdapter.ImageItem> out = new ArrayList<>();

        Cursor c = null;
        try {
            c = imageRepo.getImagesForGroup(groupId);
            while (c != null && c.moveToNext()) {
                GroupImagesAdapter.ImageItem it = new GroupImagesAdapter.ImageItem();
                it.uuid = c.getString(0);
                it.filename = c.getString(1);
                it.timestamp = c.getString(2);
                it.status = c.getInt(3);
                out.add(it);
            }
        } finally {
            if (c != null) c.close();
        }

        adapter.submit(out);

        if (actionMode != null) onSelectionCountChanged(adapter.getSelectedCount());
    }

    // ============================================================
    // Adapter callbacks
    // ============================================================

    @Override
    public void onImageClicked(String groupId, String clickedUuid) {
        // If selection mode, toggle (retain delete flow)
        if (adapter.isSelectionMode()) {
            adapter.toggleSelection(clickedUuid);
            return;
        }

        // Otherwise open bottomsheet actions
        String title = toolbar.getTitle() != null ? toolbar.getTitle().toString() : "Photo";
        PhotoActionsBottomSheet bs = PhotoActionsBottomSheet.newInstance(groupId, clickedUuid, title);
        bs.show(getSupportFragmentManager(), "photo_actions");
    }

    // Helper called by bottomsheet (Preview button)
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
        // ✅ long-press -> selection mode for delete
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
        if (actionMode != null) actionMode.setTitle(count + " selected");
    }

    // ============================================================
    // Called from BottomSheet
    // ============================================================

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

        // ✅ valid gps: block only if BOTH are ~0
        if (Double.isNaN(pin.lat) || Double.isNaN(pin.lng) ||
                (Math.abs(pin.lat) < 0.000001 && Math.abs(pin.lng) < 0.000001)) {
            Toast.makeText(this, "No GPS location for this photo.", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<PhotoPin> pins = new ArrayList<>();
        pins.add(pin);

        OsmMapDialog d = OsmMapDialog.newInstance("Pin on Map")
                .setPins(pins);

        d.show(getSupportFragmentManager(), "osm_map");
    }
    // ============================================================
    // ActionMode (delete only)
    // ============================================================

    private final ActionMode.Callback actionModeCb = new ActionMode.Callback() {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.menu_group_images_selection, menu);
            return true;
        }

        @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int id = item.getItemId();

            if (id == R.id.action_select_all) { adapter.selectAll(); return true; }
            if (id == R.id.action_clear) { adapter.clearSelection(); return true; }

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

    // ============================================================
    // Pinch-to-zoom grid
    // ============================================================

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
        int spacingPx = dp(4);
        gridDecoration = new GridSpacingItemDecoration(spanCount, spacingPx, true);
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