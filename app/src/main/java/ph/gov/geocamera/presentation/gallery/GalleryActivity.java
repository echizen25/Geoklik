package ph.gov.geocamera.presentation.gallery;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.data.sync.SyncScheduler;
import ph.gov.geocamera.data.sync.ProjectBackgroundSync;
import ph.gov.geocamera.data.sync.ProjectSyncScheduler;
import ph.gov.geocamera.presentation.home.HomeActivity;
import ph.gov.geocamera.presentation.library.LibraryActivity;
import ph.gov.geocamera.presentation.settings.SettingsActivity;

public class GalleryActivity extends AppCompatActivity implements GalleryAdapter.Callback {

    private MaterialToolbar toolbar;

    // panels
    private View cardSearch;
    private View cardFilter;

    private Spinner spYear;
    private RecyclerView rvGallery;
    private TextInputEditText etSearchSite;

    private DrawerLayout drawerLayout;
    private NavigationView navView;

    private TextView tvFilterHint;
    private TextView tvNetworkStatus;

    private SwipeRefreshLayout swipeRefresh;

    private View syncStatusRow;
    private ProgressBar pbSync;
    private TextView tvSyncProgress;

    private boolean toastShownRunning = false;

    private ImageMetaRepository imageRepo;
    private GalleryAdapter adapter;

    private String selectedYear = "ALL";
    private String searchText = "";

    private int selectedCount = 0;

    // connectivity
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean hasInternet = false;

    // ui toggles
    private boolean searchOpen = false;
    private boolean filterOpen = false;

    // sync state
    private boolean isSyncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        drawerLayout = findViewById(R.id.drawerLayout);
        navView = findViewById(R.id.navView);

        toolbar = findViewById(R.id.toolbar);
        if (toolbar == null) {
            throw new IllegalStateException("Toolbar not found. Check activity_gallery.xml for @+id/toolbar");
        }

        cardSearch = findViewById(R.id.cardSearch);
        cardFilter = findViewById(R.id.cardFilter);

        spYear = findViewById(R.id.spYear);
        rvGallery = findViewById(R.id.rvGallery);
        etSearchSite = findViewById(R.id.etSearchSite);

        tvFilterHint = findViewById(R.id.tvFilterHint);
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus);

        swipeRefresh = findViewById(R.id.swipeRefresh);

        syncStatusRow = findViewById(R.id.syncStatusRow);
        pbSync = findViewById(R.id.pbSync);
        tvSyncProgress = findViewById(R.id.tvSyncProgress);

        imageRepo = new ImageMetaRepository(this);

        // Drawer icon
        toolbar.setNavigationIcon(R.drawable.ic_menu_24);
        toolbar.setNavigationIconTint(getColor(android.R.color.white));
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout != null) drawerLayout.openDrawer(GravityCompat.START);
        });

        if (navView != null) {
            navView.setNavigationItemSelectedListener(item -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);

                int id = item.getItemId();
                if (id == R.id.nav_home) {
                    startActivity(new Intent(this, HomeActivity.class));
                    finish();
                    return true;
                }
                if (id == R.id.nav_gallery) return true;
                if (id == R.id.nav_library) {
                    startActivity(new Intent(this, LibraryActivity.class));
                    finish();
                    return true;
                }
                if (id == R.id.nav_settings) {
                    startActivity(new Intent(this, SettingsActivity.class));
                    finish();
                    return true;
                }
                return false;
            });
        }

        toolbar.setOnMenuItemClickListener(this::onToolbarMenuClick);

        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeResources(R.color.brand_green);
            swipeRefresh.setOnRefreshListener(() -> {
                if (adapter != null) adapter.clearSelection();
                updateSelectionUi(0);

                loadRoot();

                swipeRefresh.postDelayed(() -> {
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                }, 250);
            });
        }

        // RecyclerView setup
        rvGallery.setLayoutManager(new LinearLayoutManager(this));
        rvGallery.setHasFixedSize(true);
        rvGallery.setItemAnimator(null);
        rvGallery.setClipToPadding(false);

        rvGallery.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view,
                                       RecyclerView parent,
                                       RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);

                outRect.left = 0;
                outRect.right = 0;
                outRect.bottom = dp(10);

                if (position == 0) {
                    outRect.top = dp(14); // breathing space sa first card
                } else {
                    outRect.top = 0;
                }
            }
        });

        adapter = new GalleryAdapter(this, imageRepo);
        rvGallery.setAdapter(adapter);

        RecyclerViewPreloader<File> preloader =
                new RecyclerViewPreloader<>(
                        Glide.with(this),
                        adapter,
                        adapter.getPreloadSizeProvider(),
                        12
                );
        rvGallery.addOnScrollListener(preloader);

        registerConnectivityWatcher();

        if (etSearchSite != null) {
            etSearchSite.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void afterTextChanged(Editable s) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    searchText = (s == null) ? "" : s.toString();
                    if (adapter != null) adapter.clearSelection();
                    loadRoot();
                }
            });
        }

        observeUploadWork();

        loadFilters();
        loadRoot();
        updateSelectionUi(0);

        setPanelVisible(cardSearch, false);
        setPanelVisible(cardFilter, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRoot();
        applyConnectivityUi(isOnline());
        updateMenuState();

        // Seamless project sync so beneficiary/code data refreshes automatically
        syncProjectsInBackgroundThenReloadGallery(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterConnectivityWatcher();
    }


    // ============================================================
    // BACKGROUND PROJECT SYNC
    // ============================================================
    private void syncProjectsInBackgroundThenReloadGallery(boolean force) {
        ProjectBackgroundSync.syncIfNeeded(getApplicationContext(), force, updated -> {
            runOnUiThread(() -> {
                if (updated && !isFinishing() && !isDestroyed()) {
                    loadRoot();
                    updateMenuState();
                }
            });
        });
    }

    // ============================================================
    // Toolbar menu handling
    // ============================================================
    private boolean onToolbarMenuClick(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_home) {
            Intent h = new Intent(this, HomeActivity.class);
            h.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(h);
            finish();
            return true;
        }
        if (id == R.id.action_search) {
            toggleSearchPanel();
            return true;
        }

        if (id == R.id.action_filter) {
            toggleFilterPanel();
            return true;
        }

        if (id == R.id.action_export) {
            exportSelectedSitesToGallery();
            return true;
        }

        if (id == R.id.action_sync_all) {
            startSyncAll();
            return true;
        }

        return false;
    }

    private void toggleSearchPanel() {
        if (filterOpen) {
            filterOpen = false;
            hideDrop(cardFilter);
        }

        searchOpen = !searchOpen;

        if (searchOpen) {
            showDrop(cardSearch);
            if (etSearchSite != null) etSearchSite.requestFocus();
        } else {
            hideDrop(cardSearch);
        }
    }

    private void toggleFilterPanel() {
        if (searchOpen) {
            searchOpen = false;
            hideDrop(cardSearch);
        }

        filterOpen = !filterOpen;
        if (filterOpen) showDrop(cardFilter);
        else hideDrop(cardFilter);
    }

    private void setPanelVisible(View v, boolean visible) {
        if (v == null) return;
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateMenuState() {
        Menu m = toolbar.getMenu();
        if (m == null) return;

        MenuItem export = m.findItem(R.id.action_export);
        MenuItem sync = m.findItem(R.id.action_sync_all);

        if (export != null) {
            export.setEnabled(selectedCount > 0);
            export.setVisible(true);
        }

        if (sync != null) {
            sync.setEnabled(!isSyncing);
            sync.setVisible(true);
            sync.setTitle(isSyncing ? "Syncing..." : "Sync All");
        }
    }

    // ============================================================
    // Sync UI helpers
    // ============================================================
    private void setSyncUi(boolean syncing, String progressText) {
        if (!hasInternet) syncing = false;

        isSyncing = syncing;
        updateMenuState();

        if (swipeRefresh != null) swipeRefresh.setEnabled(!syncing);

        if (syncStatusRow != null) syncStatusRow.setVisibility(syncing ? View.VISIBLE : View.GONE);
        if (pbSync != null) pbSync.setVisibility(syncing ? View.VISIBLE : View.GONE);

        if (tvSyncProgress != null && progressText != null) {
            tvSyncProgress.setText(progressText);
        }
    }


    /**
     * countPendingForSync() intentionally excludes NO_PROJECT_FOUND to prevent infinite retries.
     * This method lets the user manually retry those failed items when they tap Sync again.
     */
    private int ensureRetryablePendingIfNeeded() {
        int pending = imageRepo.countPendingForSync();

        if (pending > 0) {
            return pending;
        }

        int noProjectFailed = imageRepo.countNoProjectFoundFailed();

        if (noProjectFailed > 0) {
            int reset = imageRepo.retryNoProjectFound();
            pending = imageRepo.countPendingForSync();

            Toast.makeText(
                    this,
                    "Retrying " + reset + " failed item(s)...",
                    Toast.LENGTH_SHORT
            ).show();
        }

        return pending;
    }

    private void startSyncAll() {
        if (isSyncing) {
            Toast.makeText(this, "Sync already in progress.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasInternet || !isOnline()) {
            hasInternet = false;
            applyConnectivityUi(false);
            Toast.makeText(this, "No internet connection. Sync unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }

        int pending = ensureRetryablePendingIfNeeded();
        if (pending <= 0) {
            Toast.makeText(this, "Nothing to sync.", Toast.LENGTH_SHORT).show();
            return;
        }

        toastShownRunning = false;
        setSyncUi(true, "Sync: starting...");
        Toast.makeText(this, "Preparing sync...", Toast.LENGTH_SHORT).show();
        SyncScheduler.enqueueUploadNow(getApplicationContext());
    }

    // ============================================================
    // Connectivity
    // ============================================================
    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) return false;

        boolean hasNet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

        return hasNet && validated;
    }

    private void applyConnectivityUi(boolean online) {
        hasInternet = online;

        if (tvNetworkStatus != null) {
            tvNetworkStatus.setVisibility(online ? View.GONE : View.VISIBLE);
            if (!online) tvNetworkStatus.setText("No internet. Sync disabled.");
        }

        if (!online) {
            setSyncUi(false, null);
        }

        updateMenuState();
    }

    private void registerConnectivityWatcher() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        applyConnectivityUi(isOnline());

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> applyConnectivityUi(isOnline()));
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> applyConnectivityUi(false));
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                runOnUiThread(() -> applyConnectivityUi(isOnline()));
            }
        };

        connectivityManager.registerNetworkCallback(req, networkCallback);
    }

    private void unregisterConnectivityWatcher() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    // WorkManager Observer
    // ============================================================
    private void observeUploadWork() {
        WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData(SyncScheduler.UNIQUE_UPLOAD_WORK)
                .observe(this, infos -> {
                    if (infos == null || infos.isEmpty()) {
                        setSyncUi(false, null);
                        return;
                    }

                    WorkInfo info = infos.get(0);
                    WorkInfo.State state = info.getState();

                    if (!hasInternet) {
                        setSyncUi(false, null);
                        return;
                    }

                    boolean runningOrQueued =
                            (state == WorkInfo.State.RUNNING
                                    || state == WorkInfo.State.ENQUEUED
                                    || state == WorkInfo.State.BLOCKED);

                    if (runningOrQueued) {
                        int done = info.getProgress().getInt("DONE", 0);
                        int total = info.getProgress().getInt("TOTAL", 0);
                        String site = info.getProgress().getString("SITE");

                        String label = (total > 0)
                                ? ("Sync: " + done + "/" + total)
                                : "Sync: working...";

                        if (site != null && !site.trim().isEmpty()) {
                            label += " • " + site.trim();
                        }

                        setSyncUi(true, label);

                        if (state == WorkInfo.State.RUNNING && !toastShownRunning) {
                            toastShownRunning = true;
                            Toast.makeText(this, "Sync started", Toast.LENGTH_SHORT).show();
                        }

                    } else if (state == WorkInfo.State.SUCCEEDED) {
                        setSyncUi(false, null);

                        int noProjectCount = imageRepo.countNoProjectFoundFailed();

                        if (noProjectCount > 0) {
                            Toast.makeText(
                                    this,
                                    noProjectCount + " item(s) failed: No project found for the site ID.",
                                    Toast.LENGTH_LONG
                            ).show();
                        } else {
                            Toast.makeText(this, "Sync complete", Toast.LENGTH_SHORT).show();
                        }

                        loadRoot();

                    } else if (state == WorkInfo.State.FAILED) {
                        setSyncUi(false, null);

                        int noProjectCount = imageRepo.countNoProjectFoundFailed();

                        if (noProjectCount > 0) {
                            Toast.makeText(
                                    this,
                                    noProjectCount + " item(s) failed: No project found for the site ID.",
                                    Toast.LENGTH_LONG
                            ).show();
                        } else {
                            Toast.makeText(this, "Sync failed", Toast.LENGTH_LONG).show();
                        }

                        loadRoot();

                    } else if (state == WorkInfo.State.CANCELLED) {
                        setSyncUi(false, null);
                        Toast.makeText(this, "Sync cancelled", Toast.LENGTH_SHORT).show();
                        loadRoot();

                    } else {
                        setSyncUi(false, null);
                    }
                });
    }

    // ============================================================
    // Filters / loading
    // ============================================================
    private void loadFilters() {
        List<String> years = new ArrayList<>();
        years.add("ALL");

        Cursor yc = imageRepo.getDistinctYears();
        try {
            while (yc.moveToNext()) {
                String y = yc.getString(0);
                if (y != null && !y.trim().isEmpty()) years.add(y.trim());
            }
        } finally {
            yc.close();
        }

        spYear.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, years));
        spYear.setOnItemSelectedListener(new SimpleItemSelectedListener(pos -> {
            selectedYear = years.get(pos);
            if (adapter != null) adapter.clearSelection();
            loadRoot();
        }));
    }

    private void loadRoot() {
        adapter.loadSites("ALL", selectedYear, searchText);
    }

    // ============================================================
    // GalleryAdapter.Callback
    // ============================================================
    @Override
    public void onSyncSiteClicked(String siteId, String year, boolean alreadySynced) {
        if (!hasInternet) {
            Toast.makeText(this, "No internet connection.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (alreadySynced) {
            Toast.makeText(this, "Already synced.", Toast.LENGTH_SHORT).show();
            return;
        }

        int pending = ensureRetryablePendingIfNeeded();
        if (pending <= 0) {
            Toast.makeText(this, "Nothing to sync.", Toast.LENGTH_SHORT).show();
            return;
        }

        toastShownRunning = false;
        setSyncUi(true, "Sync: starting... • " + siteId);
        SyncScheduler.enqueueUploadNow(getApplicationContext());
    }

    @Override
    public void onSelectionChanged(int selectedCount) {
        updateSelectionUi(selectedCount);
    }

    @Override
    public void onBulkSyncRequested(List<String> siteIds) {
        if (siteIds == null || siteIds.isEmpty()) {
            Toast.makeText(this, "No sites selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasInternet) {
            Toast.makeText(this, "No internet connection.", Toast.LENGTH_SHORT).show();
            return;
        }

        int pending = ensureRetryablePendingIfNeeded();
        if (pending <= 0) {
            Toast.makeText(this, "Nothing to sync.", Toast.LENGTH_SHORT).show();
            return;
        }

        toastShownRunning = false;
        setSyncUi(true, "Sync: starting... (" + siteIds.size() + " site(s))");
        SyncScheduler.enqueueUploadNow(getApplicationContext());

        adapter.clearSelection();
    }

    @Override
    public String getSelectedYear() {
        return selectedYear;
    }

    // ============================================================
    // EXPORT
    // ============================================================
    private void exportSelectedSitesToGallery() {
        if (android.os.Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        2001
                );

                Toast.makeText(this,
                        "Storage permission required. Please allow and retry.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        List<String> siteIds = adapter.getSelectedSiteIds();
        if (siteIds == null || siteIds.isEmpty()) {
            Toast.makeText(this, "No sites selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        Cursor c = null;
        int exported = 0, missing = 0, failed = 0;
        String firstErrorMessage = null;

        try {
            c = imageRepo.getImagesForExportBySites(siteIds, "ALL", selectedYear);

            while (c.moveToNext()) {
                String filename = c.getString(3);
                String motherfolder = c.getString(4);
                String siteid = c.getString(2);
                String sessiondate = c.getString(5);
                String description = c.getString(6);

                File src = new File(filename);
                if (!src.exists()) {
                    missing++;
                    continue;
                }

                try {
                    exportOneToGallery(src, motherfolder, siteid, sessiondate, description);
                    exported++;
                } catch (Exception ex) {
                    failed++;
                    if (firstErrorMessage == null) {
                        firstErrorMessage = ex.getMessage();
                        if (firstErrorMessage == null) firstErrorMessage = ex.toString();
                    }
                }
            }

            if (failed > 0 && firstErrorMessage != null) {
                Toast.makeText(this,
                        "Export error: " + firstErrorMessage,
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        "Exported: " + exported +
                                (missing > 0 ? " | Missing: " + missing : ""),
                        Toast.LENGTH_LONG).show();
            }

            adapter.clearSelection();

        } catch (Exception e) {
            Toast.makeText(this,
                    "Export failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        } finally {
            if (c != null) c.close();
        }
    }

    private Uri exportOneToGallery(File sourceFile,
                                   String motherfolder,
                                   String siteId,
                                   String sessionDate,
                                   String description) throws Exception {

        String safeMother = safeFolder(motherfolder == null ? "PROJECT_YYYY" : motherfolder);
        String safeSite = safeFolder(siteId == null ? "UNCAT" : siteId);
        String safeDate = safeFolder(sessionDate == null ? "UNKNOWN_DATE" : sessionDate);
        String safeDesc = safeFolder(description == null ? "GENERAL" : description);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {

            String relativePath = Environment.DIRECTORY_PICTURES
                    + "/GeoCamera/"
                    + safeMother + "/"
                    + safeSite + "/"
                    + safeDate + "/"
                    + safeDesc + "/";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, sourceFile.getName());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed.");

            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = resolver.openOutputStream(uri)) {

                if (out == null) throw new IllegalStateException("OpenOutputStream failed.");

                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                out.flush();
            } catch (Exception e) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                }
                throw e;
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);

            return uri;
        }

        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

        File destDir = new File(pictures,
                "GeoCamera/" + safeMother + "/" + safeSite + "/" + safeDate + "/" + safeDesc);

        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IllegalStateException("Failed to create folder: " + destDir.getAbsolutePath());
        }

        File destFile = new File(destDir, sourceFile.getName());

        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = new java.io.FileOutputStream(destFile)) {

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.flush();
        }

        android.media.MediaScannerConnection.scanFile(
                this,
                new String[]{destFile.getAbsolutePath()},
                new String[]{"image/jpeg"},
                null
        );

        return Uri.fromFile(destFile);
    }

    private String safeFolder(String input) {
        if (input == null) return "GENERAL";
        String s = input.trim();
        if (s.isEmpty()) return "GENERAL";
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        s = s.replaceAll("\\s+", "_");
        if (s.length() > 50) s = s.substring(0, 50);
        return s;
    }

    private void showDrop(View v) {
        if (v == null) return;

        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        v.setAlpha(0f);
        v.setTranslationY(-dp(10));
        v.setScaleX(0.985f);
        v.setScaleY(0.985f);

        v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void hideDrop(View v) {
        if (v == null) return;

        v.animate().cancel();

        v.animate()
                .alpha(0f)
                .translationY(-dp(8))
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setDuration(140)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    v.setVisibility(View.GONE);
                    v.setAlpha(1f);
                    v.setTranslationY(0f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                })
                .start();
    }

    private void updateSelectionUi(int count) {
        selectedCount = count;

        boolean hasSelection = count > 0;

        if (tvFilterHint != null) {
            tvFilterHint.setText(hasSelection
                    ? "Selected sites: " + count + " (Export enabled)"
                    : "Long-press to select sites. Export uses selected sites.");
        }

        if (tvNetworkStatus != null) {
            tvNetworkStatus.setVisibility(hasInternet ? View.GONE : View.VISIBLE);
        }

        updateMenuState();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}