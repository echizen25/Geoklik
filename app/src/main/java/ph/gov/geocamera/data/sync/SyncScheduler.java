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
     * Queue a photo upload pass.
     *
     * Photo synchronization is intentionally MANUAL now. GeoCameraActivity still
     * calls this compatibility entry point after a successful local save, but
     * those calls are ignored. Gallery's explicit "Sync All" action passes the
     * application context and is allowed to enqueue the worker.
     *
     * Project-list/background metadata refresh is separate from photo upload and
     * is not affected by this rule.
     */
    public static void enqueueUploadNow(@NonNull Context context) {
        // A capture must finish as a local PENDING photo. Do not silently upload
        // just because a network is available. The user starts uploads from
        // Gallery > Sync All.
        String callerClass = context.getClass().getName();
        if ("ph.gov.geocamera.presentation.geocamera.GeoCameraActivity".equals(callerClass)) {
            return;
        }

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
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE;
            } else if (retryBackoffQueued) {
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

    public static void cancelSync(@NonNull Context context) {
        WorkManager.getInstance(context)
                .cancelUniqueWork(UNIQUE_UPLOAD_WORK);
    }
}
