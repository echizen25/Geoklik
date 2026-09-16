package ph.gov.geocamera.data.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ph.gov.geocamera.data.remote.ApiProjectItem;
import ph.gov.geocamera.data.remote.ProjectApiService;
import ph.gov.geocamera.data.repository.ProjectAdminAreaRepository;
import ph.gov.geocamera.data.repository.ProjectGeofenceRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;

/** Reusable silent background project/capture-target sync. */
public final class ProjectBackgroundSync {

    private static final String PREFS_PROJECT_SYNC = "project_sync_prefs";
    private static final String KEY_LAST_PROJECT_SYNC = "last_project_sync";
    private static final String KEY_GEOFENCE_CACHE_VERSION = "geofence_cache_version";
    private static final String KEY_PROJECT_RECONCILE_VERSION = "project_reconcile_version";

    private static final int GEOFENCE_CACHE_VERSION = 2;
    // Versioned independently from the normal 6-hour timestamp so an app update
    // can force one authoritative cleanup of stale project rows already on device.
    private static final int PROJECT_RECONCILE_VERSION = 1;
    private static final long PROJECT_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ProjectBackgroundSync() {}

    public interface Callback {
        void onFinished(boolean updated);
    }

    public static void syncIfNeeded(@NonNull Context context,
                                    boolean force,
                                    @Nullable Callback callback) {
        Context appContext = context.getApplicationContext();

        if (!RUNNING.compareAndSet(false, true)) {
            if (callback != null) callback.onFinished(false);
            return;
        }

        EXECUTOR.execute(() -> {
            boolean updated = false;
            try {
                ProjectRepository repo = new ProjectRepository(appContext);
                ProjectGeofenceRepository geofenceRepo = new ProjectGeofenceRepository(appContext);
                ProjectAdminAreaRepository adminAreaRepo = new ProjectAdminAreaRepository(appContext);
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_PROJECT_SYNC, Context.MODE_PRIVATE);

                boolean hasLocalProjects = !repo.getProjectList().isEmpty();
                long lastSync = prefs.getLong(KEY_LAST_PROJECT_SYNC, 0L);
                long now = System.currentTimeMillis();
                boolean intervalExpired = (now - lastSync) >= PROJECT_SYNC_INTERVAL_MS;
                boolean needsProjectAreaBootstrap =
                        prefs.getInt(KEY_GEOFENCE_CACHE_VERSION, 0) < GEOFENCE_CACHE_VERSION;
                boolean needsProjectReconciliation =
                        prefs.getInt(KEY_PROJECT_RECONCILE_VERSION, 0) < PROJECT_RECONCILE_VERSION;

                // A new reconciliation version bypasses the normal 6-hour gate once.
                // This cleans stale rows that were saved by older upsert-only builds.
                if (!force && hasLocalProjects && !intervalExpired
                        && !needsProjectAreaBootstrap && !needsProjectReconciliation) {
                    return;
                }

                ProjectApiService apiService = new ProjectApiService();
                List<ApiProjectItem> items = apiService.fetchProjects();
                boolean authoritative = apiService.wasLastFetchAuthoritative();

                if (items != null) {
                    // Only the complete /capture-targets response may remove stale rows.
                    // If the API fell back to legacy /projects, keep upsert-only behavior
                    // because that endpoint does not contain Project/Activity targets.
                    repo.saveProjectsFromApi(items, authoritative);
                    geofenceRepo.saveFromApi(items); // legacy radius cache retained only for compatibility
                    adminAreaRepo.saveFromApi(items);

                    boolean adminAreaContractSeen = false;
                    for (ApiProjectItem item : items) {
                        if (item != null && item.adminAreaMetadataAvailable) {
                            adminAreaContractSeen = true;
                            break;
                        }
                    }

                    SharedPreferences.Editor editor = prefs.edit()
                            .putLong(KEY_LAST_PROJECT_SYNC, System.currentTimeMillis());
                    if (adminAreaContractSeen) {
                        editor.putInt(KEY_GEOFENCE_CACHE_VERSION, GEOFENCE_CACHE_VERSION);
                    }
                    // Do not mark reconciliation complete on legacy fallback. We want
                    // the next eligible run to retry against the authoritative feed.
                    if (authoritative) {
                        editor.putInt(KEY_PROJECT_RECONCILE_VERSION, PROJECT_RECONCILE_VERSION);
                    }
                    editor.apply();
                    updated = true;
                }
            } catch (Exception ignored) {
                // Silent background sync only. A failed fetch never prunes local data.
            } finally {
                RUNNING.set(false);
                if (callback != null) callback.onFinished(updated);
            }
        });
    }

    public static void resetLastSync(@NonNull Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_PROJECT_SYNC, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_PROJECT_SYNC)
                .remove(KEY_GEOFENCE_CACHE_VERSION)
                .remove(KEY_PROJECT_RECONCILE_VERSION)
                .apply();
    }
}
