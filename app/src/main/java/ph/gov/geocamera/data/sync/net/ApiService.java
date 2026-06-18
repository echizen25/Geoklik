package ph.gov.geocamera.data.sync.net;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface ApiService {

    @Multipart
    @POST("api/geocamera/upload")
    Call<UploadResponse> uploadPhoto(
            @Part MultipartBody.Part file,

            @Part("uuid") RequestBody uuid,
            @Part("project") RequestBody project,
            @Part("siteId") RequestBody siteId,
            @Part("userId") RequestBody userId,
            @Part("groupId") RequestBody groupId,

            // server folder keys
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

            // funding_code = project_id sa app
            @Part("fundingCode") RequestBody fundingCode,

            // for tbl_progress upsert
            @Part("progressTimestamp") RequestBody progressTimestamp
    );
}
