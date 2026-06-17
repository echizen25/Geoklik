package ph.gov.geocamera.presentation.home;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.remote.ApiProjectItem;
import ph.gov.geocamera.data.remote.ProjectApiService;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.presentation.gallery.GalleryActivity;
import ph.gov.geocamera.presentation.geocamera.GeoCameraActivity;
import ph.gov.geocamera.presentation.library.LibraryActivity;
import ph.gov.geocamera.presentation.settings.SettingsActivity;

public class HomeActivity extends AppCompatActivity {

    private static final String PREFS_SYNC = "project_sync_prefs";
    private static final String KEY_LAST_PROJECT_SYNC = "last_project_sync";
    private static final long PROJECT_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L; // 6 hours

    private DrawerLayout drawerLayout;
    private MaterialToolbar topAppBar;
    private NavigationView navView;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean syncRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        drawerLayout = findViewById(R.id.drawerLayout);
        topAppBar = findViewById(R.id.topAppBar);
        navView = findViewById(R.id.navView);

        topAppBar.bringToFront();
        topAppBar.invalidate();

        setupDrawerHamburger();
        setupDrawerMenu();
        setupIconClicks();
        setupBackBehavior();

        // Auto sync projects silently in background
        syncProjectsIfNeeded(false);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Optional refresh check when returning to Home
        syncProjectsIfNeeded(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdown();
    }

    private void setupDrawerHamburger() {
        if (topAppBar == null) return;

        topAppBar.setNavigationOnClickListener(v -> {
            if (drawerLayout != null) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
    }

    private void setupDrawerMenu() {
        if (navView == null) return;

        navView.setCheckedItem(R.id.nav_home);

        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);

            if (id == R.id.nav_home) {
                item.setChecked(true);
                return true;
            }

            if (id == R.id.nav_gallery) {
                item.setChecked(true);
                startActivity(new Intent(this, GalleryActivity.class));
                return true;
            }

            if (id == R.id.nav_library) {
                item.setChecked(true);
                startActivity(new Intent(this, LibraryActivity.class));
                return true;
            }

            if (id == R.id.nav_settings) {
                item.setChecked(true);
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }

            return false;
        });
    }

    private void setupIconClicks() {
        ImageButton btnCapture = findViewById(R.id.btnCapture);
        ImageButton btnGallery = findViewById(R.id.btnGallery);
        ImageButton btnLibrary = findViewById(R.id.btnLibrary);
        ImageButton btnSettings = findViewById(R.id.btnSettings);

        if (btnCapture != null) {
            btnCapture.setOnClickListener(v ->
                    animateIconClick(v, () ->
                            startActivity(new Intent(this, GeoCameraActivity.class))
                    )
            );
        }

        if (btnGallery != null) {
            btnGallery.setOnClickListener(v ->
                    animateIconClick(v, () ->
                            startActivity(new Intent(this, GalleryActivity.class))
                    )
            );
        }

        if (btnLibrary != null) {
            btnLibrary.setOnClickListener(v ->
                    animateIconClick(v, () ->
                            startActivity(new Intent(this, LibraryActivity.class))
                    )
            );
        }

        if (btnSettings != null) {
            btnSettings.setOnClickListener(v ->
                    animateIconClick(v, () ->
                            startActivity(new Intent(this, SettingsActivity.class))
                    )
            );
        }
    }

    private void setupBackBehavior() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    finish();
                }
            }
        });
    }

    private void animateIconClick(@NonNull View icon, @NonNull Runnable action) {
        icon.animate()
                .scaleX(0.96f)
                .scaleY(0.96f)
                .alpha(0.85f)
                .setDuration(120)
                .withEndAction(() -> icon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(120)
                        .withEndAction(action)
                        .start())
                .start();
    }

    private void syncProjectsIfNeeded(boolean respectInterval) {
        if (syncRunning) return;

        ProjectRepository repo = new ProjectRepository(this);
        boolean hasLocalProjects = repo.hasAnyProjects();

        SharedPreferences prefs = getSharedPreferences(PREFS_SYNC, MODE_PRIVATE);
        long lastSync = prefs.getLong(KEY_LAST_PROJECT_SYNC, 0L);
        long now = System.currentTimeMillis();

        boolean intervalExpired = (now - lastSync) >= PROJECT_SYNC_INTERVAL_MS;

        boolean shouldSync;
        if (!hasLocalProjects) {
            // First install / empty DB => sync agad
            shouldSync = true;
        } else if (respectInterval) {
            // On resume, sync only if stale
            shouldSync = intervalExpired;
        } else {
            // On first home load, sync if stale or no previous sync
            shouldSync = intervalExpired || lastSync == 0L;
        }

        if (!shouldSync) return;

        syncRunning = true;

        ioExecutor.execute(() -> {
            try {
                ProjectApiService apiService = new ProjectApiService();
                List<ApiProjectItem> items = apiService.fetchProjects();

                if (items != null && !items.isEmpty()) {
                    repo.saveProjectsFromApi(items);
                    prefs.edit().putLong(KEY_LAST_PROJECT_SYNC, System.currentTimeMillis()).apply();
                }
            } catch (Exception ignored) {
                // silent sync lang para seamless, no toast
            } finally {
                mainHandler.post(() -> syncRunning = false);
            }
        });
    }
}