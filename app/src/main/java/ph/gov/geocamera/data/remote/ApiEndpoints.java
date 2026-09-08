package ph.gov.geocamera.data.remote;

/**
 * Single source of truth for GeoKlik Android API routes.
 *
 * API application base:
 * https://geoklik.philmech.gov.ph/api/
 *
 * Canonical GeoKlik boundary:
 * /api/geoklik/*
 */
public final class ApiEndpoints {

    private ApiEndpoints() {
        // Utility class.
    }

    public static final String HOST_URL =
            "https://geoklik.philmech.gov.ph/";

    // Retrofit base URL must end with '/'.
    public static final String API_BASE_URL =
            HOST_URL + "api/";

    public static final String GEOKLIK_PATH = "geoklik/";
    public static final String GEOKLIK_BASE_URL = API_BASE_URL + GEOKLIK_PATH;

    // Relative routes used by Retrofit.
    public static final String UPLOAD_PATH = GEOKLIK_PATH + "upload";

    // Absolute routes used by HttpURLConnection services.
    public static final String PROJECTS_URL = GEOKLIK_BASE_URL + "projects";
    public static final String APP_VERSION_URL = GEOKLIK_BASE_URL + "app-version";
    public static final String PING_URL = GEOKLIK_BASE_URL + "ping";
    public static final String HEALTH_URL = GEOKLIK_BASE_URL + "health";
}
