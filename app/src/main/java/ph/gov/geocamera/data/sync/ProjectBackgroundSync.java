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

    // v2 adds the server-provided INFRA mun_code + brgy_code metadata. Reusing
    // the existing bootstrap key forces one refresh for users upgrading from the
    // earlier radius prototype, even if their project list was synced recently.
    private static final int GEOFENCE_CACHE_VERSION = 2;
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

                boolean hasLocalProjects = repo.hasAnyProjects();
                long lastSync = prefs.getLong(KEY_LAST_PROJECT_SYNC, 0L);
                long now = System.currentTimeMillis();
                boolean intervalExpired = (now - lastSync) >= PROJECT_SYNC_INTERVAL_MS;
                boolean needsProjectAreaBootstrap =
                        prefs.getInt(KEY_GEOFENCE_CACHE_VERSION, 0) < GEOFENCE_CACHE_VERSION;

                if (!force && hasLocalProjects && !intervalExpired && !needsProjectAreaBootstrap) {
                    return;
                }

                ProjectApiService apiService = new ProjectApiService();
                List<ApiProjectItem> items = apiService.fetchProjects();

                if (items != null && !items.isEmpty()) {
                    repo.saveProjectsFromApi(items);
                    geofenceRepo.saveFromApi(items); // legacy radius cache retained only for compatibility
                    adminAreaRepo.saveFromApi(items);

                    // Do not mark v2 complete merely because an older server still
                    // exposes radius metadata. We specifically need the new
                    // munCode/brgyCode contract for Infrastructure authorization.
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
                    editor.apply();
                    updated = true;
                }
            } catch (Exception ignored) {
                // Silent background sync only.
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
                .apply();
    }
}
