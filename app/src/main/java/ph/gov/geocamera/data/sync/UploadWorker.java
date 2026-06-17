package ph.gov.geocamera.data.sync;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.data.sync.net.ApiClient;
import ph.gov.geocamera.data.sync.net.ApiService;
import ph.gov.geocamera.data.sync.net.UploadResponse;
import retrofit2.Response;

public class UploadWorker extends Worker {

    private static final String TAG = "UPLOAD";
    private static final int BATCH_LIMIT = 5;
    private static final String ERR_NO_PROJECT_FOUND = "NO_PROJECT_FOUND";

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {

        Log.d(TAG, "Worker START");

        ImageMetaRepository repo = new ImageMetaRepository(getApplicationContext());
        repo.resetStuckUploading();

        ApiService api = ApiClient.get().create(ApiService.class);

        Cursor c = null;
        boolean shouldRetry = false;
        int processed = 0;

        try {
            int totalAll = getInputData().getInt("TOTAL_ALL", repo.countPendingForSync());

            int remainingStart = repo.countPendingForSync();
            int doneBase = Math.max(0, totalAll - remainingStart);

            c = repo.getPendingUploads(BATCH_LIMIT);

            if (c == null || c.getCount() == 0) {
                setProgressAsync(new Data.Builder()
                        .putInt("DONE", totalAll)
                        .putInt("TOTAL", totalAll)
                        .build());

                Log.d(TAG, "No pending uploads. DONE");
                return Result.success();
            }

            setProgressAsync(new Data.Builder()
                    .putInt("DONE", doneBase)
                    .putInt("TOTAL", totalAll)
                    .build());

            while (c.moveToNext()) {

                String uuid              = safe(c.getString(0));
                String filePath          = safe(c.getString(1));

                // local tbl_imagemeta.project = funding/display value only
                String project           = safe(c.getString(2));

                // IMPORTANT:
                // local tbl_imagemeta.siteid = actual server-side project_id
                String siteId            = safe(c.getString(3));

                String userId            = safe(c.getString(4));
                String groupId           = safe(c.getString(5));

                // optional extra funding/display field from query if present
                String fundingCode       = safe(c.getString(6));

                Double lat = c.isNull(7) ? null : c.getDouble(7);
                Double lng = c.isNull(8) ? null : c.getDouble(8);
                Double acc = c.isNull(9) ? null : c.getDouble(9);

                String location          = safe(c.getString(10));
                String errorAtLoc        = safe(c.getString(11));
                String description       = safe(c.getString(12));
                String timestamp         = safe(c.getString(13));
                String sessionDate       = safe(c.getString(14));
                String folderRel         = safe(c.getString(15));
                String progressTimestamp = safe(c.getString(16));
                String groupRemarks      = safe(c.getString(17));

                String motherfolder = folderRel;
                if (motherfolder.contains("/")) {
                    motherfolder = motherfolder.split("/")[0];
                }
                if (motherfolder.isEmpty()) {
                    motherfolder = "PROJECT_0000";
                }

                Log.d(TAG, "Processing uuid=" + uuid
                        + ", siteId(project_id)=" + siteId
                        + ", groupId=" + groupId
                        + ", project(funding)=" + project
                        + ", fundingCode=" + fundingCode
                        + ", filePath=" + filePath);

                if (uuid.isEmpty() || filePath.isEmpty()) {
                    repo.markUploadFail(uuid, "MISSING_UUID_OR_FILEPATH");
                }
                else if (groupId.isEmpty()) {
                    repo.markUploadFail(uuid, "MISSING_GROUPID");
                }
                else if (siteId.isEmpty()) {
                    // actual server project_id is required
                    repo.markUploadFail(uuid, ERR_NO_PROJECT_FOUND);
                }
                else {
                    File file = new File(filePath);
                    if (!file.exists()) {
                        repo.markUploadFail(uuid, "FILE_MISSING");
                    }
                    else {
                        repo.markUploading(uuid);

                        try {
                            RequestBody fileBody = RequestBody.create(
                                    file,
                                    MediaType.parse("image/jpeg")
                            );

                            MultipartBody.Part photoPart =
                                    MultipartBody.Part.createFormData("file", file.getName(), fileBody);

                            Response<UploadResponse> resp = api.uploadPhoto(
                                    photoPart,
                                    text(uuid),
                                    text(project),            // funding/display value only
                                    text(siteId),             // actual server project_id
                                    text(userId),
                                    text(groupId),
                                    text(motherfolder),
                                    text(sessionDate),
                                    text(description),
                                    text(groupRemarks),
                                    text(timestamp),
                                    text(lat == null ? "" : String.valueOf(lat)),
                                    text(lng == null ? "" : String.valueOf(lng)),
                                    text(acc == null ? "" : String.valueOf(acc)),
                                    text(location),
                                    text(errorAtLoc),
                                    text(fundingCode),        // optional metadata only
                                    text(progressTimestamp)
                            ).execute();

                            if (resp.isSuccessful() && resp.body() != null) {
                                UploadResponse body = resp.body();

                                Log.d(TAG, "API response uuid=" + uuid
                                        + ", ok=" + body.ok
                                        + ", error=" + body.error
                                        + ", path=" + body.path
                                        + ", status=" + body.status);

                                if (body.ok) {
                                    String serverPath = body.path == null ? "" : body.path;
                                    repo.markUploadSuccess(uuid, serverPath);
                                } else {
                                    String err = safe(body.error);
                                    if (err.isEmpty()) err = "SERVER_FAIL";

                                    repo.markUploadFail(uuid, err);

                                    if (!ERR_NO_PROJECT_FOUND.equalsIgnoreCase(err)) {
                                        shouldRetry = true;
                                    }
                                }

                            } else {
                                int code = resp.code();
                                String rawErr = readErrorBody(resp.errorBody());

                                Log.d(TAG, "HTTP error uuid=" + uuid
                                        + ", code=" + code
                                        + ", body=" + rawErr);

                                String finalErr = "HTTP_" + code + (rawErr.isEmpty() ? "" : (": " + rawErr));
                                repo.markUploadFail(uuid, finalErr);

                                if (code >= 500 || code == 429) {
                                    shouldRetry = true;
                                }
                            }

                        } catch (IOException io) {
                            Log.e(TAG, "IO error uuid=" + uuid, io);
                            repo.markUploadFail(uuid, "IO_" + io.getClass().getSimpleName());
                            shouldRetry = true;

                        } catch (Exception e) {
                            Log.e(TAG, "Unexpected error uuid=" + uuid, e);
                            repo.markUploadFail(uuid, "EX_" + e.getClass().getSimpleName());
                            shouldRetry = true;
                        }
                    }
                }

                processed++;

                setProgressAsync(new Data.Builder()
                        .putInt("DONE", doneBase + processed)
                        .putInt("TOTAL", totalAll)
                        .putString("UUID", uuid)
                        .putString("SITE", siteId)
                        .build());
            }

            int remainingAfter = repo.countPendingForSync();
            if (remainingAfter > 0 && shouldRetry) {
                Log.d(TAG, "More pending remaining and retry needed: " + remainingAfter);
                return Result.retry();
            }

            setProgressAsync(new Data.Builder()
                    .putInt("DONE", totalAll)
                    .putInt("TOTAL", totalAll)
                    .build());

            Log.d(TAG, "Worker END SUCCESS");
            return Result.success();

        } finally {
            if (c != null) c.close();
        }
    }

    private static RequestBody text(String s) {
        return RequestBody.create(
                s == null ? "" : s,
                MediaType.parse("text/plain")
        );
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String readErrorBody(ResponseBody b) {
        if (b == null) return "";
        try {
            String s = b.string();
            if (s == null) return "";
            s = s.trim();
            return s.length() > 250 ? s.substring(0, 250) : s;
        } catch (Exception e) {
            return "";
        }
    }
}