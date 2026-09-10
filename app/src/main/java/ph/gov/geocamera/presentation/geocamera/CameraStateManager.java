package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.Locale;

import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.BarangayBoundaryRepository;
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

    private static final long AREA_GPS_MAX_AGE_MS = 15_000L;
    private static final float AREA_MAX_GPS_ACCURACY_M = 30f;
    private static final long AREA_LOCAL_CACHE_MS = 5_000L;
    private static final long AREA_SYNC_RETRY_MS = 30_000L;

    private final ImageButton captureButton;
    private final TextView statusText;
    private final Context appContext;
    private final CameraPrefs cameraPrefs;
    private final ProjectAdminAreaRepository adminAreaRepository;
    private final BarangayBoundaryRepository barangayBoundaryRepository;
    private State state = State.WAITING_FOR_GPS;

    private String cachedProjectId = "";
    private ProjectAdminAreaRepository.Record cachedAdminArea;
    private long cachedAdminAreaAt = 0L;
    private long lastAreaSyncRequestAt = 0L;

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
        this.barangayBoundaryRepository = source == null
                ? null
                : new BarangayBoundaryRepository(source.getApplicationContext());

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
                ? evaluateInfrastructureBarangay()
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
        }
    }

    /**
     * Final project-location rule:
     * - INFRA: current real GPS must fall inside the barangay registered by the
     *   project's mun_code + brgy_code.
     * - PROJECT_ACTIVITY: no barangay restriction; normal GeoKlik GPS rules only.
     * - PERSONAL: no barangay restriction and remains local-only.
     *
     * Unlike the old radius prototype, missing INFRA administrative metadata is
     * fail-closed. This prevents a project from another region from being used
     * simply because no exact latitude/radius was configured.
     */
    private AreaDecision evaluateInfrastructureBarangay() {
        if (cameraPrefs == null
                || adminAreaRepository == null
                || barangayBoundaryRepository == null
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

        if (!area.hasCodes()) {
            return AreaDecision.block("PROJECT BARANGAY NOT SET");
        }

        Location gps = getFreshGpsLocation();
        if (gps == null) {
            return AreaDecision.block("GPS REQUIRED FOR PROJECT BARANGAY");
        }

        if (!gps.hasAccuracy() || gps.getAccuracy() > AREA_MAX_GPS_ACCURACY_M) {
            String accuracy = gps.hasAccuracy()
                    ? String.format(Locale.US, "±%.0fm", gps.getAccuracy())
                    : "unknown";
            return AreaDecision.block("BARANGAY GPS WEAK • " + accuracy);
        }

        BarangayBoundaryRepository.Decision decision = barangayBoundaryRepository.evaluate(
                area.municipalityCode,
                area.barangayCode,
                gps.getLatitude(),
                gps.getLongitude()
        );

        if (decision.status == BarangayBoundaryRepository.Status.INSIDE) {
            String name = clean(decision.barangayName);
            return AreaDecision.allow(
                    name.isEmpty() ? "READY • PROJECT BARANGAY" : "READY • " + name
            );
        }

        if (decision.status == BarangayBoundaryRepository.Status.OUTSIDE) {
            String name = clean(decision.barangayName);
            return AreaDecision.block(
                    name.isEmpty()
                            ? "OUTSIDE PROJECT BARANGAY"
                            : "OUTSIDE • PROJECT BRGY " + name
            );
        }

        if (decision.status == BarangayBoundaryRepository.Status.LOADING) {
            return AreaDecision.block("LOADING PROJECT BARANGAY…");
        }

        if (decision.status == BarangayBoundaryRepository.Status.INVALID_CODES) {
            return AreaDecision.block("PROJECT LOCATION CODE INVALID");
        }

        return AreaDecision.block("PROJECT BARANGAY CHECK FAILED");
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
