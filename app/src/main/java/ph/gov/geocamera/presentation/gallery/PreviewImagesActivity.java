package ph.gov.geocamera.presentation.gallery;

import android.app.AlertDialog;
import android.database.Cursor;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.repository.ImageMetaRepository;

public class PreviewImagesActivity extends AppCompatActivity implements PreviewPagerAdapter.Callback {

    public static final String EXTRA_UUID = "uuid";
    public static final String EXTRA_GROUP_ID = "groupId";
    public static final String EXTRA_START_INDEX = "startIndex";
    public static final String EXTRA_TITLE = "title";

    private com.google.android.material.appbar.MaterialToolbar toolbar;
    private ViewPager2 pager;

    private ImageMetaRepository repo;
    private PreviewPagerAdapter adapter;

    private String groupId = "";
    private int startIndex = 0;

    private final List<PreviewPagerAdapter.PhotoItem> photos = new ArrayList<>();
    private final Set<String> selectedUuids = new HashSet<>();

    private MenuItem miDelete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview_images);

        repo = new ImageMetaRepository(this);

        toolbar = findViewById(R.id.toolbar);
        pager = findViewById(R.id.viewPager);

        toolbar.setNavigationOnClickListener(v -> finish());

        groupId = (getIntent() != null) ? getIntent().getStringExtra(EXTRA_GROUP_ID) : null;
        if (groupId == null) groupId = "";
        groupId = groupId.trim();

        if (groupId.isEmpty()) {
            Toast.makeText(this, "Invalid group.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        startIndex = Math.max(0, getIntent().getIntExtra(EXTRA_START_INDEX, 0));
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        toolbar.setTitle((title != null && !title.trim().isEmpty()) ? title : "Preview");

        adapter = new PreviewPagerAdapter(this, this);
        pager.setAdapter(adapter);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateToolbarSubtitle();
                invalidateOptionsMenu();
            }
        });

        loadFromDbAndOpenRequested();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_preview_images, menu);
        miDelete = menu.findItem(R.id.action_delete);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem del = menu.findItem(R.id.action_delete);
        if (del != null) {
            del.setTitle(selectedUuids.isEmpty() ? "Delete" : ("Delete (" + selectedUuids.size() + ")"));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {
            onDeletePressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadFromDbAndOpenRequested() {
        photos.clear();

        Cursor c = null;
        try {
            c = repo.getImagesForGroup(groupId); // uuid, filename, timestamp, status
            while (c != null && c.moveToNext()) {
                PreviewPagerAdapter.PhotoItem it = new PreviewPagerAdapter.PhotoItem();
                it.uuid = c.getString(0);
                it.filename = c.getString(1);
                it.timestamp = c.getString(2);
                it.status = c.getInt(3);
                photos.add(it);
            }
        } finally {
            if (c != null) c.close();
        }

        if (photos.isEmpty()) {
            Toast.makeText(this, "No images.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String jumpUuid = getIntent().getStringExtra(EXTRA_UUID);
        int jumpIndex = -1;
        if (jumpUuid != null) {
            for (int i = 0; i < photos.size(); i++) {
                if (jumpUuid.equals(photos.get(i).uuid)) { jumpIndex = i; break; }
            }
        }

        adapter.submit(photos, selectedUuids);

        int safeIndex = (jumpIndex >= 0)
                ? jumpIndex
                : Math.min(startIndex, photos.size() - 1);

        pager.setCurrentItem(safeIndex, false);
        updateToolbarSubtitle();
        invalidateOptionsMenu();
    }

    private void updateToolbarSubtitle() {
        int pos = pager.getCurrentItem();
        int total = photos.size();

        if (!selectedUuids.isEmpty()) {
            toolbar.setSubtitle("Selected: " + selectedUuids.size());
            return;
        }

        if (total > 0 && pos >= 0 && pos < total) toolbar.setSubtitle((pos + 1) + " / " + total);
        else toolbar.setSubtitle("");
    }

    private void onDeletePressed() {
        if (photos.isEmpty()) return;

        final List<PreviewPagerAdapter.PhotoItem> toDelete = new ArrayList<>();

        if (!selectedUuids.isEmpty()) {
            for (PreviewPagerAdapter.PhotoItem p : photos) {
                if (p.uuid != null && selectedUuids.contains(p.uuid)) toDelete.add(p);
            }
        } else {
            int pos = pager.getCurrentItem();
            if (pos >= 0 && pos < photos.size()) toDelete.add(photos.get(pos));
        }

        if (toDelete.isEmpty()) return;

        String msg = (toDelete.size() == 1)
                ? "Delete this photo?"
                : "Delete " + toDelete.size() + " selected photos?";

        new AlertDialog.Builder(this)
                .setTitle("Confirm delete")
                .setMessage(msg)
                .setPositiveButton("DELETE", (d, w) -> doDelete(toDelete))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doDelete(List<PreviewPagerAdapter.PhotoItem> toDelete) {
        int deletedCount = 0;

        for (PreviewPagerAdapter.PhotoItem p : toDelete) {
            if (p.filename != null) {
                try { new File(p.filename).delete(); } catch (Exception ignored) {}
            }
            if (p.uuid != null) repo.deleteImageByUuid(p.uuid);
            deletedCount++;
        }

        selectedUuids.clear();
        Toast.makeText(this, "Deleted: " + deletedCount, Toast.LENGTH_SHORT).show();

        int current = pager.getCurrentItem();
        loadFromDbAndOpenRequested();

        if (!photos.isEmpty()) {
            int safe = Math.min(current, photos.size() - 1);
            pager.setCurrentItem(safe, false);
        }
    }

    @Override
    public void onPhotoLongPressed(String uuid) {
        if (uuid == null) return;

        if (selectedUuids.contains(uuid)) selectedUuids.remove(uuid);
        else selectedUuids.add(uuid);

        adapter.setSelection(selectedUuids);
        updateToolbarSubtitle();
        invalidateOptionsMenu();
    }

    @Override
    public void onPhotoTapped() {
        // optional: toggle toolbar show/hide
    }
}