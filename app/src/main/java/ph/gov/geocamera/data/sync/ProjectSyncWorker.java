package ph.gov.geocamera.data.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional WorkManager worker for periodic background project sync.
 */
public class ProjectSyncWorker extends Worker {

    public ProjectSyncWorker(@NonNull Context context,
                             @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(true);

        ProjectBackgroundSync.syncIfNeeded(
                getApplicationContext(),
                false,
                updated -> latch.countDown()
        );

        try {
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            if (!completed) success.set(false);
        } catch (InterruptedException e) {
            success.set(false);
            Thread.currentThread().interrupt();
        }

        return success.get() ? Result.success() : Result.retry();
    }
}
