package ph.gov.geocamera.data.sync.net;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface ApiService {

    // Existing Infrastructure upload contract. Keep unchanged for production compatibility.
    @Multipart
    @POST("api/geocamera/upload")
    Call<UploadResponse> uploadPhoto(
            @Part MultipartBody.Part file,

            @Part("uuid") RequestBody uuid,
            @Part("project") RequestBody project,
            @Part("siteId") RequestBody siteId,
            @Part("userId") RequestBody userId,
            @Part("groupId") RequestBody groupId,

            @Part("motherfolder") RequestBody motherfolder,
            @Part("sessiondate") RequestBody sessiondate,
            @Part("description") RequestBody description,
            @Part("groupRemarks") RequestBody groupRemarks,

            @Part("timestamp") RequestBody timestamp,

            @Part("lat") RequestBody lat,
            @Part("lng") RequestBody lng,
            @Part("acc") RequestBody acc,

            @Part("location") RequestBody location,
            @Part("errorAtLoc") RequestBody errorAtLoc,

            @Part("fundingCode") RequestBody fundingCode,
            @Part("progressTimestamp") RequestBody progressTimestamp
    );

    // Project Activity intentionally has its own server route and tables.
    // It reuses the same local WorkManager/status flow but does not touch
    // INFRA tbl_groups/tbl_images/tbl_progress on the server.
    @Multipart
    @POST("api/project-activity/upload")
    Call<UploadResponse> uploadProjectActivityPhoto(
            @Part MultipartBody.Part file,

            @Part("uuid") RequestBody uuid,
            @Part("siteId") RequestBody siteId,
            @Part("activityProjectId") RequestBody activityProjectId,
            @Part("userId") RequestBody userId,
            @Part("groupId") RequestBody groupId,

            @Part("sessiondate") RequestBody sessiondate,
            @Part("description") RequestBody description,
            @Part("groupRemarks") RequestBody groupRemarks,
            @Part("timestamp") RequestBody timestamp,

            @Part("lat") RequestBody lat,
            @Part("lng") RequestBody lng,
            @Part("acc") RequestBody acc,
            @Part("location") RequestBody location,
            @Part("errorAtLoc") RequestBody errorAtLoc
    );
}
