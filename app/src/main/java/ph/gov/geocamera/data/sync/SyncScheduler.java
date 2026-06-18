package ph.gov.geocamera.data.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Data;

import java.util.concurrent.TimeUnit;

import ph.gov.geocamera.data.repository.ImageMetaRepository;

public class SyncScheduler {

    public static final String UNIQUE_UPLOAD_WORK = "geocamera_upload_work";

    /**
     * Queue upload immediately.
     * - Keeps existing upload work if already running/enqueued
     * - Requires network
     * - Exponential retry
     */
    public static void enqueueUploadNow(@NonNull Context context) {

        ImageMetaRepository repo = new ImageMetaRepository(context);
        repo.resetStuckUploading();

        int totalAllPending = repo.countPendingForSync();

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

        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        UNIQUE_UPLOAD_WORK,
                        ExistingWorkPolicy.KEEP,
                        request
                );
    }

    /**
     * Optional: cancel running sync.
     */
    public static void cancelSync(@NonNull Context context) {
        WorkManager.getInstance(context)
                .cancelUniqueWork(UNIQUE_UPLOAD_WORK);
    }
}
