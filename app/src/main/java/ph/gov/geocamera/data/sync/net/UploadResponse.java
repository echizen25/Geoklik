package ph.gov.geocamera.data.sync.net;

public class UploadResponse {
    public boolean ok;
    public String uuid;

    // server relative path: mother/site/date/uuid.jpg
    public String path;

    public String status; // e.g. "SYNCED"
    public String error;
}