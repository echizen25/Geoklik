package ph.gov.geocamera.presentation.home;

import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final int REQ_IN_APP_UPDATE = 5001;

    private DrawerLayout drawerLayout;
    private MaterialToolbar topAppBar;
    private NavigationView navView;

    private AppUpdateManager appUpdateManager;
    private InstallStateUpdatedListener installStateUpdatedListener;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        drawerLayout = findViewById(R.id.drawerLayout);
        topAppBar = findViewById(R.id.topAppBar);
        navView = findViewById(R.id.navView);

        setupDrawerHamburger();
        setupDrawerMenu();
        setupIconClicks();
        setupBackBehavior();

        setupInAppUpdates();
        checkForFlexibleUpdate();

        // Auto sync projects silently in background
        syncProjectsIfNeeded(false);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Resume flexible update if already downloaded.
        checkDownloadedFlexibleUpdate();

        // Refresh only if sync interval already expired
        syncProjectsIfNeeded(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (appUpdateManager != null && installStateUpdatedListener != null) {
            try {
                appUpdateManager.unregisterListener(installStateUpdatedListener);
            } catch (Exception ignored) {
            }
        }

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

            if (drawerLayout != null) {
                drawerLayout.closeDrawer(GravityCompat.START);
            }

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

    // ============================================================
    // GOOGLE PLAY IN-APP UPDATE
    // ============================================================
    private void setupInAppUpdates() {
        try {
            appUpdateManager = AppUpdateManagerFactory.create(this);

            installStateUpdatedListener = state -> {
                if (isFinishing() || isDestroyed()) return;

                if (state.installStatus() == InstallStatus.DOWNLOADED) {
                    showUpdateDownloadedSnackbar();
                } else if (state.installStatus() == InstallStatus.FAILED) {
                    Toast.makeText(
                            this,
                            "GeoKlik update failed. Please try again from Play Store.",
                            Toast.LENGTH_LONG
                    ).show();
                }
            };

            appUpdateManager.registerListener(installStateUpdatedListener);
        } catch (Exception ignored) {
            appUpdateManager = null;
            installStateUpdatedListener = null;
        }
    }

    private void checkForFlexibleUpdate() {
        if (appUpdateManager == null) return;

        try {
            appUpdateManager.getAppUpdateInfo()
                    .addOnSuccessListener(appUpdateInfo -> {
                        if (isFinishing() || isDestroyed()) return;

                        boolean updateAvailable =
                                appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE;

                        boolean flexibleAllowed =
                                appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE);

                        if (updateAvailable && flexibleAllowed) {
                            startFlexibleUpdate(appUpdateInfo);
                        }
                    })
                    .addOnFailureListener(e -> {
                        // Ignore silently. App update check should never block GeoKlik.
                    });
        } catch (Exception ignored) {
            // Some devices / stores may not support Play Core update flow.
        }
    }

    private void checkDownloadedFlexibleUpdate() {
        if (appUpdateManager == null) return;

        try {
            appUpdateManager.getAppUpdateInfo()
                    .addOnSuccessListener(appUpdateInfo -> {
                        if (isFinishing() || isDestroyed()) return;

                        if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                            showUpdateDownloadedSnackbar();
                        }
                    })
                    .addOnFailureListener(e -> {
                        // Ignore silently.
                    });
        } catch (Exception ignored) {
        }
    }

    private void startFlexibleUpdate(@NonNull AppUpdateInfo appUpdateInfo) {
        if (appUpdateManager == null || isFinishing() || isDestroyed()) return;

        try {
            appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    this,
                    AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE)
                            .setAllowAssetPackDeletion(true)
                            .build(),
                    REQ_IN_APP_UPDATE
            );
        } catch (IntentSender.SendIntentException e) {
            Toast.makeText(this, "Unable to start GeoKlik update.", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            // Ignore. Update flow should not crash the app.
        }
    }

    private void showUpdateDownloadedSnackbar() {
        View root = findViewById(android.R.id.content);
        if (root == null || appUpdateManager == null || isFinishing() || isDestroyed()) return;

        try {
            Snackbar.make(
                            root,
                            "GeoKlik update downloaded. Restart app to install.",
                            Snackbar.LENGTH_INDEFINITE
                    )
                    .setAction("RESTART", v -> {
                        try {
                            if (appUpdateManager != null) {
                                appUpdateManager.completeUpdate();
                            }
                        } catch (Exception ignored) {
                        }
                    })
                    .show();
        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // PROJECT SYNC
    // ============================================================
    private void syncProjectsIfNeeded(boolean respectInterval) {
        if (!syncRunning.compareAndSet(false, true)) {
            return;
        }

        ProjectRepository repo = new ProjectRepository(getApplicationContext());
        SharedPreferences prefs = getSharedPreferences(PREFS_SYNC, MODE_PRIVATE);

        boolean hasLocalProjects = repo.hasAnyProjects();
        long lastSync = prefs.getLong(KEY_LAST_PROJECT_SYNC, 0L);
        long now = System.currentTimeMillis();

        boolean intervalExpired = (now - lastSync) >= PROJECT_SYNC_INTERVAL_MS;

        boolean shouldSync;
        if (!hasLocalProjects) {
            // First install / empty DB => sync immediately
            shouldSync = true;
        } else if (respectInterval) {
            // On resume, sync only if stale
            shouldSync = intervalExpired;
        } else {
            // On first home load, sync if stale or no previous sync
            shouldSync = intervalExpired || lastSync == 0L;
        }

        if (!shouldSync) {
            syncRunning.set(false);
            return;
        }

        ioExecutor.execute(() -> {
            try {
                ProjectApiService apiService = new ProjectApiService();
                List<ApiProjectItem> items = apiService.fetchProjects();

                if (items != null && !items.isEmpty()) {
                    repo.saveProjectsFromApi(items);
                    prefs.edit()
                            .putLong(KEY_LAST_PROJECT_SYNC, System.currentTimeMillis())
                            .apply();
                }
            } catch (Exception ignored) {
                // Silent sync only, no toast
            } finally {
                syncRunning.set(false);
            }
        });
    }
}
