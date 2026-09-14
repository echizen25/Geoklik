package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.Locale;

import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.MunicipalityBoundaryRepository;
import ph.gov.geocamera.data.repository.ProjectAdminAreaRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;
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
     * Rollout strategy:
     * - DIAGNOSE=true: evaluate INFRA municipality and show useful warnings/details.
     * - ENFORCE=false: never disable the shutter because of the project municipality yet.
     *
     * This lets field testing expose bad/missing mun_code data without preventing
     * legitimate documentation. Once the project-location feed is verified, only
     * ENFORCE_INFRA_PROJECT_CITY needs to be enabled.
     */
    private static final boolean DIAGNOSE_INFRA_PROJECT_CITY = true;
    private static final boolean ENFORCE_INFRA_PROJECT_CITY = false;

    private static final long AREA_GPS_MAX_AGE_MS = 15_000L;
    private static final float AREA_MAX_GPS_ACCURACY_M = 30f;
    private static final long AREA_LOCAL_CACHE_MS = 5_000L;
    private static final long AREA_SYNC_RETRY_MS = 30_000L;
    private static final long NOTICE_REPEAT_MS = 20_000L;

    private final ImageButton captureButton;
    private final TextView statusText;
    private final Context uiContext;
    private final Context appContext;
    private final CameraPrefs cameraPrefs;
    private final ProjectAdminAreaRepository adminAreaRepository;
    private final MunicipalityBoundaryRepository municipalityBoundaryRepository;
    private final ProjectRepository projectRepository;
    private State state = State.WAITING_FOR_GPS;

    private String cachedProjectId = "";
    private ProjectAdminAreaRepository.Record cachedAdminArea;
    private long cachedAdminAreaAt = 0L;
    private long lastAreaSyncRequestAt = 0L;

    private String lastNoticeKey = "";
    private long lastNoticeAt = 0L;

    CameraStateManager(ImageButton captureButton, TextView statusText) {
        this.captureButton = captureButton;
        this.statusText = statusText;

        Context source = captureButton != null
                ? captureButton.getContext()
                : (statusText != null ? statusText.getContext() : null);
        this.uiContext = source;
        this.appContext = source == null ? null : source.getApplicationContext();
        this.cameraPrefs = source == null ? null : new CameraPrefs(source);
        this.adminAreaRepository = source == null
                ? null
                : new ProjectAdminAreaRepository(source.getApplicationContext());
        this.municipalityBoundaryRepository = source == null
                ? null
                : new MunicipalityBoundaryRepository(source.getApplicationContext());
        this.projectRepository = source == null
                ? null
                : new ProjectRepository(source.getApplicationContext());

        // The status line doubles as a location-diagnostics affordance. A field
        // user can tap LOCATION WARNING / LOCATION CHECK to see the selected
        // project code, expected mun_code, GPS coordinates, and exact check result.
        if (this.statusText != null) {
            this.statusText.setOnClickListener(v -> {
                if (!DIAGNOSE_INFRA_PROJECT_CITY || state != State.READY) return;
                AreaDecision area = evaluateInfrastructureMunicipality();
                if (area.message != null) showLocationDetails(area.message);
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

        AreaDecision area = next == State.READY && DIAGNOSE_INFRA_PROJECT_CITY
                ? evaluateInfrastructureMunicipality()
                : AreaDecision.notApplicable();

        // Project-location diagnostics are intentionally non-blocking while
        // ENFORCE is false. Normal GeoKlik GPS/camera state still controls READY.
        boolean ready = next == State.READY
                && (!ENFORCE_INFRA_PROJECT_CITY || area.allowed);

        if (captureButton != null) {
            captureButton.setEnabled(ready);
            captureButton.setAlpha(ready ? 1f : 0.35f);
        }

        if (statusText != null) {
            String text;
            if (next == State.READY && area.message != null) {
                if (area.allowed) {
                    text = area.message;
                } else if (ENFORCE_INFRA_PROJECT_CITY) {
                    text = area.message;
                } else {
                    text = diagnosticStatusLabel(area.message);
                }
            } else {
                text = label(next);
            }

            statusText.setText(text);
            statusText.setVisibility(View.VISIBLE);
            statusText.setClickable(next == State.READY && area.message != null);
            statusText.setContentDescription(
                    next == State.READY && area.message != null
                            ? text + ". Tap for location details."
                            : text
            );
        }

        if (next == State.READY && !area.allowed && area.message != null) {
            showLocationNotice(area.message, false);
        }
    }

    /**
     * Evaluate only Infrastructure projects. Project Activity and Personal are
     * never municipality-restricted. During diagnostic rollout a failed result
     * is displayed but does not disable the shutter.
     */
    private AreaDecision evaluateInfrastructureMunicipality() {
        if (!DIAGNOSE_INFRA_PROJECT_CITY) return AreaDecision.notApplicable();

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
            return AreaDecision.allow("READY • LOCATION MATCH");
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
            // Force only when the selected INFRA project has no cached admin-area
            // metadata. This fixes the case where the ordinary 6-hour project sync
            // is recent but mun_code was never cached by an older app build.
            ProjectBackgroundSync.syncIfNeeded(appContext, true, updated -> {
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

    private String diagnosticStatusLabel(String message) {
        String m = clean(message).toUpperCase(Locale.US);
        if (m.startsWith("OUTSIDE PROJECT CITY")) return "LOCATION WARNING • TAP DETAILS";
        if (m.startsWith("SYNC PROJECT LOCATION")) return "LOCATION CHECK • SYNCING…";
        if (m.startsWith("LOADING PROJECT CITY")) return "LOCATION CHECK • LOADING…";
        if (m.startsWith("PROJECT CITY/MUNICIPALITY NOT SET")) return "LOCATION DATA MISSING • TAP DETAILS";
        if (m.startsWith("PROJECT MUNICIPALITY CODE INVALID")) return "LOCATION CODE ERROR • TAP DETAILS";
        if (m.startsWith("GPS REQUIRED")) return "LOCATION CHECK • GPS REQUIRED";
        if (m.startsWith("CITY GPS WEAK")) return "LOCATION CHECK • GPS WEAK";
        return "LOCATION CHECK WARNING • TAP DETAILS";
    }

    private void showLocationNotice(String technicalMessage, boolean force) {
        if (captureButton == null) return;

        String key = clean(technicalMessage);
        if (key.isEmpty()) key = "LOCATION CHECK WARNING";

        long now = System.currentTimeMillis();
        if (!force
                && key.equals(lastNoticeKey)
                && (now - lastNoticeAt) < NOTICE_REPEAT_MS) {
            return;
        }

        lastNoticeKey = key;
        lastNoticeAt = now;

        Snackbar.make(
                captureButton,
                explainDiagnostic(key),
                Snackbar.LENGTH_LONG
        ).setAction("DETAILS", v -> showLocationDetails(key)).show();
    }

    private String explainDiagnostic(String message) {
        String m = clean(message).toUpperCase(Locale.US);
        String projectCode = selectedProjectCode();
        String munCode = expectedMunicipalityCode();
        String prefix = projectCode.isEmpty() ? "INFRA" : projectCode;

        if (m.startsWith("OUTSIDE PROJECT CITY")) {
            return "Location warning: " + prefix + " expects municipality code "
                    + valueOrDash(munCode) + ". Capture is allowed while validation is in test mode.";
        }
        if (m.startsWith("PROJECT CITY/MUNICIPALITY NOT SET")) {
            return "Location data missing for " + prefix
                    + ": mun_code is empty. Capture is currently allowed.";
        }
        if (m.startsWith("SYNC PROJECT LOCATION")) {
            return "Location data for " + prefix
                    + " is not cached yet. GeoKlik is refreshing Projects; capture remains allowed.";
        }
        if (m.startsWith("LOADING PROJECT CITY")) {
            return "Loading municipality boundary for code " + valueOrDash(munCode)
                    + ". Capture remains allowed during validation testing.";
        }
        if (m.startsWith("GPS REQUIRED")) {
            return "Location check needs a fresh GPS fix. Capture remains controlled by the normal camera GPS rules.";
        }
        if (m.startsWith("CITY GPS WEAK")) {
            return "Location check GPS is weak. Tap DETAILS to see the selected project and expected municipality code.";
        }
        if (m.startsWith("PROJECT MUNICIPALITY CODE INVALID")) {
            return "Location code error: " + prefix + " has mun_code " + valueOrDash(munCode)
                    + ", which cannot be matched to the boundary dataset.";
        }
        return "Location check warning for " + prefix + ". Tap DETAILS to inspect the project/location values.";
    }

    private void showLocationDetails(String technicalMessage) {
        if (uiContext == null) return;

        String projectId = cameraPrefs == null ? "" : clean(cameraPrefs.getSiteId());
        String projectCode = selectedProjectCode();
        ProjectAdminAreaRepository.Record area = projectId.isEmpty()
                ? null
                : getCachedAdminArea(projectId);
        String munCode = area == null ? "" : clean(area.municipalityCode);
        String brgyCode = area == null ? "" : clean(area.barangayCode);
        Location gps = getFreshGpsLocation();

        StringBuilder message = new StringBuilder();
        message.append("Project Code: ").append(valueOrDash(projectCode)).append('\n');
        message.append("Project ID: ").append(valueOrDash(projectId)).append('\n');
        message.append("Expected mun_code: ").append(valueOrDash(munCode)).append('\n');
        message.append("brgy_code (reference only): ").append(valueOrDash(brgyCode)).append('\n');

        if (gps != null) {
            message.append("GPS: ")
                    .append(String.format(Locale.US, "%.6f, %.6f", gps.getLatitude(), gps.getLongitude()))
                    .append('\n');
            message.append("GPS accuracy: ")
                    .append(gps.hasAccuracy()
                            ? String.format(Locale.US, "±%.0f m", gps.getAccuracy())
                            : "unknown")
                    .append('\n');
        } else {
            message.append("GPS: no fresh GPS fix\n");
        }

        message.append("Result: ").append(valueOrDash(technicalMessage)).append('\n');
        message.append("Capture: ")
                .append(ENFORCE_INFRA_PROJECT_CITY ? "BLOCKED when invalid" : "ALLOWED (diagnostic mode)");

        try {
            new MaterialAlertDialogBuilder(uiContext)
                    .setTitle("Project Location Check")
                    .setMessage(message.toString())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {
            showLocationNotice(technicalMessage, true);
        }
    }

    private String selectedProjectCode() {
        if (cameraPrefs == null || projectRepository == null) return "";
        String projectId = clean(cameraPrefs.getSiteId());
        if (projectId.isEmpty()) return "";
        return clean(projectRepository.getProjectCodeById(projectId));
    }

    private String expectedMunicipalityCode() {
        if (cameraPrefs == null || adminAreaRepository == null) return "";
        String projectId = clean(cameraPrefs.getSiteId());
        if (projectId.isEmpty()) return "";
        ProjectAdminAreaRepository.Record area = getCachedAdminArea(projectId);
        return area == null ? "" : clean(area.municipalityCode);
    }

    private static String valueOrDash(String value) {
        String v = clean(value);
        return v.isEmpty() ? "—" : v;
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
