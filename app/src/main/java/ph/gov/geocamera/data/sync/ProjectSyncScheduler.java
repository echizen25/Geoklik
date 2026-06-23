package ph.gov.geocamera.data.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Schedules project masterlist sync in the background.
 */
public final class ProjectSyncScheduler {

    public static final String UNIQUE_PROJECT_SYNC_NOW = "geoklik_project_sync_now";
    public static final String UNIQUE_PROJECT_SYNC_PERIODIC = "geoklik_project_sync_periodic";

    private ProjectSyncScheduler() {
    }

    public static void enqueueProjectSyncNow(@NonNull Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(ProjectSyncWorker.class)
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                20,
                                TimeUnit.SECONDS
                        )
                        .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        UNIQUE_PROJECT_SYNC_NOW,
                        ExistingWorkPolicy.REPLACE,
                        request
                );
    }

    public static void schedulePeriodicProjectSync(@NonNull Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(ProjectSyncWorker.class, 6, TimeUnit.HOURS)
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                20,
                                TimeUnit.SECONDS
                        )
                        .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        UNIQUE_PROJECT_SYNC_PERIODIC,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request
                );
    }
}
