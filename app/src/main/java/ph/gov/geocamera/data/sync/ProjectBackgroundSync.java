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
import ph.gov.geocamera.data.repository.ProjectGeofenceRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;

/**
 * Reusable silent background project sync.
 *
 * Use this anywhere:
 * ProjectBackgroundSync.syncIfNeeded(context, false, callback);
 * ProjectBackgroundSync.syncIfNeeded(context, true, callback); // force refresh
 *
 * Silent behavior:
 * - no toast
 * - no spinner
 * - skips if recently synced
 * - syncs immediately if tbl_projects is empty
 * - safe to call from Home/Gallery/Camera/SetSite
 */
public final class ProjectBackgroundSync {

    private static final String PREFS_PROJECT_SYNC = "project_sync_prefs";
    private static final String KEY_LAST_PROJECT_SYNC = "last_project_sync";
    private static final String KEY_GEOFENCE_CACHE_VERSION = "geofence_cache_version";
    private static final int GEOFENCE_CACHE_VERSION = 1;
    private static final long PROJECT_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L; // 6 hours

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ProjectBackgroundSync() {
    }

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
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_PROJECT_SYNC, Context.MODE_PRIVATE);

                boolean hasLocalProjects = repo.hasAnyProjects();
                long lastSync = prefs.getLong(KEY_LAST_PROJECT_SYNC, 0L);
                long now = System.currentTimeMillis();
                boolean intervalExpired = (now - lastSync) >= PROJECT_SYNC_INTERVAL_MS;
                boolean needsGeofenceBootstrap =
                        prefs.getInt(KEY_GEOFENCE_CACHE_VERSION, 0) < GEOFENCE_CACHE_VERSION;

                // A one-time bootstrap is required after installing the build that
                // introduced geofence metadata, even if the ordinary project cache
                // was refreshed recently by an older app version.
                if (!force && hasLocalProjects && !intervalExpired && !needsGeofenceBootstrap) {
                    return;
                }

                ProjectApiService apiService = new ProjectApiService();
                List<ApiProjectItem> items = apiService.fetchProjects();

                if (items != null && !items.isEmpty()) {
                    repo.saveProjectsFromApi(items);
                    geofenceRepo.saveFromApi(items);

                    // Only mark the geofence bootstrap complete if this response
                    // actually came from a contract that exposed geofence metadata.
                    boolean geofenceMetadataSeen = false;
                    for (ApiProjectItem item : items) {
                        if (item != null && item.geofenceMetadataAvailable) {
                            geofenceMetadataSeen = true;
                            break;
                        }
                    }

                    SharedPreferences.Editor editor = prefs.edit()
                            .putLong(KEY_LAST_PROJECT_SYNC, System.currentTimeMillis());
                    if (geofenceMetadataSeen) {
                        editor.putInt(KEY_GEOFENCE_CACHE_VERSION, GEOFENCE_CACHE_VERSION);
                    }
                    editor.apply();

                    updated = true;
                }
            } catch (Exception ignored) {
                // Silent background sync only.
            } finally {
                RUNNING.set(false);

                if (callback != null) {
                    callback.onFinished(updated);
                }
            }
        });
    }

    public static void resetLastSync(@NonNull Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_PROJECT_SYNC, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_PROJECT_SYNC)
                .remove(KEY_GEOFENCE_CACHE_VERSION)
                .apply();
    }
}
