package ph.gov.geocamera.data.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.TimeUnit;

import ph.gov.geocamera.data.repository.ImageMetaRepository;

public class SyncScheduler {

    public static final String UNIQUE_UPLOAD_WORK = "geocamera_upload_work";

    /**
     * Queue upload as soon as Android has a validated network connection.
     *
     * Behavior:
     * - keeps the original one-at-a-time upload flow
     * - if upload work is already running, append one follow-up pass so newly
     *   captured pending photos are not left behind
     * - if an older worker is sitting in retry/backoff, replace it with a fresh
     *   request so a previous API/network failure does not leave the UI stuck
     *   on "Sync: working..."
     * - requires network and keeps exponential retry for real transient errors
     */
    public static void enqueueUploadNow(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();
        final WorkManager workManager = WorkManager.getInstance(appContext);

        ListenableFuture<List<WorkInfo>> future =
                workManager.getWorkInfosForUniqueWork(UNIQUE_UPLOAD_WORK);

        future.addListener(() -> {
            boolean running = false;
            boolean retryBackoffQueued = false;

            try {
                List<WorkInfo> infos = future.get();
                if (infos != null) {
                    for (WorkInfo info : infos) {
                        if (info == null) continue;

                        if (info.getState() == WorkInfo.State.RUNNING) {
                            running = true;
                        }

                        if (info.getState() == WorkInfo.State.ENQUEUED
                                && info.getRunAttemptCount() > 0) {
                            retryBackoffQueued = true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall back to normal KEEP behavior below.
            }

            ImageMetaRepository repo = new ImageMetaRepository(appContext);

            // Only recover status=UPLOADING when no worker is actually running.
            // This avoids changing the status of a photo that is actively uploading.
            if (!running) {
                repo.resetStuckUploading();
            }

            int totalAllPending = repo.countPendingForSync();
            if (totalAllPending <= 0) return;

            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            Data input = new Data.Builder()
                    .putInt("TOTAL_ALL", totalAllPending)
                    .build();

            OneTimeWorkRequest request =
                    new OneTimeWorkRequest.Builder(UploadWorker.class)
                            .setConstraints(constraints)
                            .setInputData(input)
                            .setBackoffCriteria(
                                    BackoffPolicy.EXPONENTIAL,
                                    20,
                                    TimeUnit.SECONDS
                            )
                            .addTag(UNIQUE_UPLOAD_WORK)
                            .build();

            ExistingWorkPolicy policy;
            if (running) {
                // Current worker already has its batch. Queue one follow-up pass
                // for photos that became pending while that worker was running.
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE;
            } else if (retryBackoffQueued) {
                // A previous bad API/network attempt can leave WorkManager in
                // exponential backoff. Start fresh as soon as sync is requested again.
                policy = ExistingWorkPolicy.REPLACE;
            } else {
                policy = ExistingWorkPolicy.KEEP;
            }

            workManager.enqueueUniqueWork(
                    UNIQUE_UPLOAD_WORK,
                    policy,
                    request
            );
        }, ContextCompat.getMainExecutor(appContext));
    }

    /**
     * Optional: cancel running sync.
     */
    public static void cancelSync(@NonNull Context context) {
        WorkManager.getInstance(context)
                .cancelUniqueWork(UNIQUE_UPLOAD_WORK);
    }
}
