package ph.gov.geocamera.data.remote;

public class ApiProjectItem {
    public String projectId;
    public String code;
    public String name;
    public String beneficiary;
    public String location;
    public double cost;

    // Unified /capture-targets metadata.
    public String projectType;
    public String divisionId;
    public String divisionCode;
    public String divisionName;
    public String projectImplementors;
    public String projectDescription;
    public String dateFrom;
    public String dateTo;

    // Optional project-area geofence. Null means "not configured" and keeps
    // the existing capture behavior for backward compatibility.
    public Double geofenceLatitude;
    public Double geofenceLongitude;
    public Double geofenceRadiusMeters;

    // True only when the response contract actually included geofence fields.
    // This prevents the legacy /projects fallback from accidentally clearing a
    // previously cached geofence simply because that old endpoint has no fields.
    public boolean geofenceMetadataAvailable;
}
