package ph.gov.geocamera.presentation.library;

public class ProjectListItem {
    public String projectId;
    public String code;
    public String projectName;
    public String beneficiary;
    public String location;
    public String cost;
    public String dateAdded;
    public String dateModified;

    // Local display metadata from tbl_projects. These fields keep the Library UI
    // type-aware without changing the API or upload contracts.
    public String projectType;
    public String divisionCode;
    public String divisionName;
}
