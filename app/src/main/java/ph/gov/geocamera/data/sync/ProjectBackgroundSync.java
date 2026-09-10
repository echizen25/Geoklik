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

    // v2 adds the server-provided INFRA mun_code + brgy_code metadata. Reusing
    // the existing bootstrap key forces one refresh for users upgrading from the
    // earlier radius prototype, even if their project list was synced recently.
    private static final int GEOFENCE_CACHE_VERSION = 2;
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
                ProjectAdminAreaRepository adminAreaRepo = new ProjectAdminAreaRepository(appContext);
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_PROJECT_SYNC, Context.MODE_PRIVATE);

                boolean hasLocalProjects = repo.hasAnyProjects();
                long lastSync = prefs.getLong(KEY_LAST_PROJECT_SYNC, 0L);
                long now = System.currentTimeMillis();
                boolean intervalExpired = (now - lastSync) >= PROJECT_SYNC_INTERVAL_MS;
                boolean needsProjectAreaBootstrap =
                        prefs.getInt(KEY_GEOFENCE_CACHE_VERSION, 0) < GEOFENCE_CACHE_VERSION;

                // A one-time bootstrap is required after installing a build that
                // introduces new project-area metadata, even if an older app
                // refreshed the ordinary project list recently.
                if (!force && hasLocalProjects && !intervalExpired && !needsProjectAreaBootstrap) {
                    return;
                }

                ProjectApiService apiService = new ProjectApiService();
                List<ApiProjectItem> items = apiService.fetchProjects();

                if (items != null && !items.isEmpty()) {
                    repo.saveProjectsFromApi(items);
                    geofenceRepo.saveFromApi(items); // legacy radius cache kept for compatibility only
                    adminAreaRepo.saveFromApi(items);

                    boolean projectAreaMetadataSeen = false;
                    for (ApiProjectItem item : items) {
                        if (item != null
                                && (item.adminAreaMetadataAvailable || item.geofenceMetadataAvailable)) {
                            projectAreaMetadataSeen = true;
                            break;
                        }
                    }

                    SharedPreferences.Editor editor = prefs.edit()
                            .putLong(KEY_LAST_PROJECT_SYNC, System.currentTimeMillis());
                    if (projectAreaMetadataSeen) {
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
