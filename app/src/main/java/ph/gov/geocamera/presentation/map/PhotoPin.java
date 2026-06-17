package ph.gov.geocamera.presentation.map;

public class PhotoPin {
    public String uuid;
    public String filename;   // local file path (thumbnail)
    public double lat;
    public double lng;
    public String title;      // e.g. filename or timestamp
    public String subtitle;   // e.g. "lat,lng"
}