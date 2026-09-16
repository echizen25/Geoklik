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

    // INFRA administrative-area restriction. PROJECT_ACTIVITY deliberately
    // leaves these null because activity documentation is not barangay locked.
    public String municipalityCode;
    public String barangayCode;
    public boolean adminAreaMetadataAvailable;

    // Older optional project-area geofence fields are kept for compatibility
    // with an already-deployed API response. The new app no longer uses radius
    // authorization for Infrastructure captures.
    public Double geofenceLatitude;
    public Double geofenceLongitude;
    public Double geofenceRadiusMeters;

    // True only when the response contract actually included geofence fields.
    // This prevents the legacy /projects fallback from accidentally clearing a
    // previously cached geofence simply because that old endpoint has no fields.
    public boolean geofenceMetadataAvailable;
}
