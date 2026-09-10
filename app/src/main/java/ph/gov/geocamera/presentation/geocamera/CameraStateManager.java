package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;

import java.util.Locale;

import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.MunicipalityBoundaryRepository;
import ph.gov.geocamera.data.repository.ProjectAdminAreaRepository;
import ph.gov.geocamera.data.sync.ProjectBackgroundSync;

/** Keeps the shutter and capture-status label consistent across the capture lifecycle. */
final class CameraStateManager {

    enum State {
        SELECT_SITE,
        WAITING_FOR_GPS,
        GPS_WEAK,
        STABILIZING,
        READY,
        CHECKING_DUPLICATE,
        CAPTURING,
        ADD_DESCRIPTION,
        PROCESSING,
        SAVED,
        ERROR
    }

    /*
     * TEMPORARILY DISABLED.
     *
     * Keep the project municipality metadata/boundary implementation in place so
     * it can be re-enabled after the deployed API + local project cache flow has
     * been verified. While false, project location must never disable the shutter
     * or show a project-location block. Existing GeoKlik GPS quality rules still
     * apply in GeoCameraActivity.
     */
    private static final boolean ENFORCE_INFRA_PROJECT_CITY = false;

    private static final long AREA_GPS_MAX_AGE_MS = 15_000L;
    private static final float AREA_MAX_GPS_ACCURACY_M = 30f;
    private static final long AREA_LOCAL_CACHE_MS = 5_000L;
    private static final long AREA_SYNC_RETRY_MS = 30_000L;
    private static final long BLOCK_NOTICE_REPEAT_MS = 20_000L;

    private final ImageButton captureButton;
    private final TextView statusText;
    private final Context appContext;
    private final CameraPrefs cameraPrefs;
    private final ProjectAdminAreaRepository adminAreaRepository;
    private final MunicipalityBoundaryRepository municipalityBoundaryRepository;
    private State state = State.WAITING_FOR_GPS;

    private String cachedProjectId = "";
    private ProjectAdminAreaRepository.Record cachedAdminArea;
    private long cachedAdminAreaAt = 0L;
    private long lastAreaSyncRequestAt = 0L;

    private String lastBlockNoticeKey = "";
    private long lastBlockNoticeAt = 0L;

    CameraStateManager(ImageButton captureButton, TextView statusText) {
        this.captureButton = captureButton;
        this.statusText = statusText;

        Context source = captureButton != null
                ? captureButton.getContext()
                : (statusText != null ? statusText.getContext() : null);
        this.appContext = source == null ? null : source.getApplicationContext();
        this.cameraPrefs = source == null ? null : new CameraPrefs(source);
        this.adminAreaRepository = source == null
                ? null
                : new ProjectAdminAreaRepository(source.getApplicationContext());
        this.municipalityBoundaryRepository = source == null
                ? null
                : new MunicipalityBoundaryRepository(source.getApplicationContext());

        // The status line remains ready for the future location rule, but with
        // enforcement disabled it will not surface a project-location block.
        if (this.statusText != null) {
            this.statusText.setOnClickListener(v -> {
                if (!ENFORCE_INFRA_PROJECT_CITY || state != State.READY) return;
                AreaDecision area = evaluateInfrastructureMunicipality();
                if (!area.allowed) showBlockedNotice(area.message, true);
            });
        }

        apply(State.WAITING_FOR_GPS);
    }

    State getState() {
        return state;
    }

    boolean isBusy() {
        return state == State.CHECKING_DUPLICATE
                || state == State.CAPTURING
                || state == State.ADD_DESCRIPTION
                || state == State.PROCESSING;
    }

    void apply(State next) {
        if (next == null) return;
        state = next;

        AreaDecision area = next == State.READY
                ? evaluateInfrastructureMunicipality()
                : AreaDecision.notApplicable();

        boolean ready = next == State.READY && area.allowed;
        if (captureButton != null) {
            captureButton.setEnabled(ready);
            captureButton.setAlpha(ready ? 1f : 0.35f);
        }

        if (statusText != null) {
            String text = next == State.READY && area.message != null
                    ? area.message
                    : label(next);
            statusText.setText(text);
            statusText.setVisibility(View.VISIBLE);
            statusText.setClickable(
                    ENFORCE_INFRA_PROJECT_CITY && next == State.READY && !area.allowed
            );
            statusText.setContentDescription(
                    ENFORCE_INFRA_PROJECT_CITY && next == State.READY && !area.allowed
                            ? text + ". Tap for explanation."
                            : text
            );
        }

        if (ENFORCE_INFRA_PROJECT_CITY && next == State.READY && !area.allowed) {
            showBlockedNotice(area.message, false);
        }
    }

    /**
     * Infrastructure city/municipality validation implementation retained for a
     * later rollout. It is intentionally bypassed while
     * ENFORCE_INFRA_PROJECT_CITY is false.
     */
    private AreaDecision evaluateInfrastructureMunicipality() {
        if (!ENFORCE_INFRA_PROJECT_CITY) {
            return AreaDecision.notApplicable();
        }

        if (cameraPrefs == null
                || adminAreaRepository == null
                || municipalityBoundaryRepository == null
                || appContext == null) {
            return AreaDecision.block("PROJECT AREA CHECK UNAVAILABLE");
        }

        String documentationType = clean(cameraPrefs.getDocumentationType());
        if (!CameraPrefs.DOC_INFRA.equalsIgnoreCase(documentationType)) {
            return AreaDecision.notApplicable();
        }

        String projectId = clean(cameraPrefs.getSiteId());
        if (projectId.isEmpty() || cameraPrefs.isUncategorized()) {
            return AreaDecision.block("SELECT INFRA PROJECT");
        }

        ProjectAdminAreaRepository.Record area = getCachedAdminArea(projectId);
        if (area == null || !area.metadataAvailable) {
            requestProjectAreaSyncIfNeeded();
            return AreaDecision.block("SYNC PROJECT LOCATION");
        }

        if (!"INFRA".equalsIgnoreCase(clean(area.projectType))) {
            return AreaDecision.block("PROJECT TYPE CHECK FAILED");
        }

        if (clean(area.municipalityCode).isEmpty()) {
            return AreaDecision.block("PROJECT CITY/MUNICIPALITY NOT SET");
        }

        Location gps = getFreshGpsLocation();
        if (gps == null) {
            return AreaDecision.block("GPS REQUIRED FOR PROJECT CITY");
        }

        if (!gps.hasAccuracy() || gps.getAccuracy() > AREA_MAX_GPS_ACCURACY_M) {
            String accuracy = gps.hasAccuracy()
                    ? String.format(Locale.US, "±%.0fm", gps.getAccuracy())
                    : "unknown";
            return AreaDecision.block("CITY GPS WEAK • " + accuracy);
        }

        MunicipalityBoundaryRepository.Decision decision =
                municipalityBoundaryRepository.evaluate(
                        area.municipalityCode,
                        gps.getLatitude(),
                        gps.getLongitude()
                );

        if (decision.status == MunicipalityBoundaryRepository.Status.INSIDE) {
            return AreaDecision.allow("READY • PROJECT CITY/MUNICIPALITY");
        }

        if (decision.status == MunicipalityBoundaryRepository.Status.OUTSIDE) {
            return AreaDecision.block("OUTSIDE PROJECT CITY/MUNICIPALITY");
        }

        if (decision.status == MunicipalityBoundaryRepository.Status.LOADING) {
            return AreaDecision.block("LOADING PROJECT CITY/MUNICIPALITY…");
        }

        if (decision.status == MunicipalityBoundaryRepository.Status.INVALID_CODE) {
            return AreaDecision.block("PROJECT MUNICIPALITY CODE INVALID");
        }

        return AreaDecision.block("PROJECT CITY CHECK FAILED");
    }

    private ProjectAdminAreaRepository.Record getCachedAdminArea(String projectId) {
        long now = System.currentTimeMillis();
        if (projectId.equalsIgnoreCase(cachedProjectId)
                && (now - cachedAdminAreaAt) < AREA_LOCAL_CACHE_MS) {
            return cachedAdminArea;
        }

        cachedProjectId = projectId;
        cachedAdminAreaAt = now;
        cachedAdminArea = adminAreaRepository.getByProjectId(projectId);
        return cachedAdminArea;
    }

    private void requestProjectAreaSyncIfNeeded() {
        long now = System.currentTimeMillis();
        if ((now - lastAreaSyncRequestAt) < AREA_SYNC_RETRY_MS) return;
        lastAreaSyncRequestAt = now;
        try {
            ProjectBackgroundSync.syncIfNeeded(appContext, false, updated -> {
                if (updated) {
                    cachedAdminAreaAt = 0L;
                    cachedAdminArea = null;
                }
            });
        } catch (Exception ignored) {}
    }

    private Location getFreshGpsLocation() {
        try {
            LocationManager lm = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return null;

            Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps == null) return null;
            if (gps.isFromMockProvider()) return null;

            long ageMs = Math.abs(System.currentTimeMillis() - gps.getTime());
            if (ageMs > AREA_GPS_MAX_AGE_MS) return null;
            return gps;
        } catch (SecurityException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void showBlockedNotice(String technicalMessage, boolean force) {
        if (!ENFORCE_INFRA_PROJECT_CITY || captureButton == null) return;

        String key = clean(technicalMessage);
        if (key.isEmpty()) key = "CAPTURE BLOCKED";

        long now = System.currentTimeMillis();
        if (!force
                && key.equals(lastBlockNoticeKey)
                && (now - lastBlockNoticeAt) < BLOCK_NOTICE_REPEAT_MS) {
            return;
        }

        lastBlockNoticeKey = key;
        lastBlockNoticeAt = now;

        Snackbar.make(
                captureButton,
                explainBlockReason(key),
                Snackbar.LENGTH_LONG
        ).setAction("OK", v -> { }).show();
    }

    private String explainBlockReason(String message) {
        String m = clean(message).toUpperCase(Locale.US);

        if (m.startsWith("OUTSIDE PROJECT CITY")) {
            return "Capture blocked: this INFRA project is registered in a different city/municipality.";
        }
        if (m.startsWith("PROJECT CITY/MUNICIPALITY NOT SET")) {
            return "Capture blocked: this INFRA project has no municipality code in the synced project data.";
        }
        if (m.startsWith("SYNC PROJECT LOCATION")) {
            return "Capture blocked: project location data is not synced yet. Connect to the internet and refresh Projects.";
        }
        if (m.startsWith("LOADING PROJECT CITY")) {
            return "GeoKlik is loading the project city/municipality boundary. Keep internet on for the first check, then try again.";
        }
        if (m.startsWith("GPS REQUIRED")) {
            return "Capture blocked: a fresh GPS fix is required to verify that you are inside the project's city/municipality.";
        }
        if (m.startsWith("CITY GPS WEAK")) {
            return "Capture blocked: GPS accuracy is too weak for the city/municipality check. Move to an open area and wait for a better fix.";
        }
        if (m.startsWith("PROJECT MUNICIPALITY CODE INVALID")) {
            return "Capture blocked: the project's municipality code cannot be matched to the boundary dataset.";
        }
        if (m.startsWith("SELECT INFRA PROJECT")) {
            return "Capture blocked: select a valid Infrastructure project first.";
        }
        if (m.startsWith("PROJECT TYPE CHECK FAILED")) {
            return "Capture blocked: the selected project's capture type could not be verified.";
        }
        if (m.startsWith("PROJECT AREA CHECK UNAVAILABLE")) {
            return "Capture blocked: project location validation is temporarily unavailable.";
        }
        return "Capture blocked: GeoKlik could not verify the Infrastructure project location.";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String label(State s) {
        switch (s) {
            case SELECT_SITE: return "SELECT SITE";
            case WAITING_FOR_GPS: return "WAITING FOR GPS";
            case GPS_WEAK: return "GPS WEAK";
            case STABILIZING: return "STABILIZING";
            case READY: return "READY";
            case CHECKING_DUPLICATE: return "CHECKING";
            case CAPTURING: return "CAPTURING";
            case ADD_DESCRIPTION: return "ADD DESCRIPTION";
            case PROCESSING: return "PROCESSING";
            case SAVED: return "SAVED";
            case ERROR: return "ERROR";
            default: return "WAITING FOR GPS";
        }
    }

    private static final class AreaDecision {
        final boolean allowed;
        final String message;

        private AreaDecision(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }

        static AreaDecision notApplicable() {
            return new AreaDecision(true, null);
        }

        static AreaDecision allow(String message) {
            return new AreaDecision(true, message);
        }

        static AreaDecision block(String message) {
            return new AreaDecision(false, message);
        }
    }
}
