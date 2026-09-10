package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.Locale;

import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.ProjectGeofenceRepository;

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

    private static final long GEOFENCE_GPS_MAX_AGE_MS = 15_000L;
    private static final float GEOFENCE_MAX_GPS_ACCURACY_M = 30f;

    private final ImageButton captureButton;
    private final TextView statusText;
    private final Context appContext;
    private final CameraPrefs cameraPrefs;
    private final ProjectGeofenceRepository geofenceRepository;
    private State state = State.WAITING_FOR_GPS;

    CameraStateManager(ImageButton captureButton, TextView statusText) {
        this.captureButton = captureButton;
        this.statusText = statusText;

        Context source = captureButton != null
                ? captureButton.getContext()
                : (statusText != null ? statusText.getContext() : null);
        this.appContext = source == null ? null : source.getApplicationContext();
        this.cameraPrefs = source == null ? null : new CameraPrefs(source);
        this.geofenceRepository = source == null
                ? null
                : new ProjectGeofenceRepository(source.getApplicationContext());

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

        GeofenceDecision geofence = next == State.READY
                ? evaluateProjectArea()
                : GeofenceDecision.notApplicable();

        boolean ready = next == State.READY && geofence.allowed;
        if (captureButton != null) {
            captureButton.setEnabled(ready);
            captureButton.setAlpha(ready ? 1f : 0.35f);
        }

        if (statusText != null) {
            String text = next == State.READY && geofence.message != null
                    ? geofence.message
                    : label(next);
            statusText.setText(text);
            statusText.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Enforces a configured project geofence without changing the existing
     * GeoCameraActivity GPS/upload flow. No geofence row means legacy behavior.
     * PERSONAL always bypasses this check because it is local-only.
     */
    private GeofenceDecision evaluateProjectArea() {
        if (cameraPrefs == null || geofenceRepository == null || appContext == null) {
            return GeofenceDecision.notApplicable();
        }

        String documentationType = clean(cameraPrefs.getDocumentationType());
        if (CameraPrefs.DOC_PERSONAL.equalsIgnoreCase(documentationType)) {
            return GeofenceDecision.notApplicable();
        }

        String projectId = clean(cameraPrefs.getSiteId());
        if (projectId.isEmpty() || cameraPrefs.isUncategorized()) {
            return GeofenceDecision.notApplicable();
        }

        ProjectGeofenceRepository.Config config = geofenceRepository.getByProjectId(projectId);
        if (config == null) {
            // Backward compatibility: projects without configured coordinates
            // continue to use the existing capture rules.
            return GeofenceDecision.notApplicable();
        }

        Location gps = getFreshGpsLocation();
        if (gps == null) {
            return GeofenceDecision.block("GPS REQUIRED FOR PROJECT AREA");
        }

        if (!gps.hasAccuracy() || gps.getAccuracy() > GEOFENCE_MAX_GPS_ACCURACY_M) {
            String accuracy = gps.hasAccuracy()
                    ? String.format(Locale.US, "±%.0fm", gps.getAccuracy())
                    : "unknown";
            return GeofenceDecision.block("AREA GPS WEAK • " + accuracy);
        }

        float[] distanceResult = new float[1];
        Location.distanceBetween(
                gps.getLatitude(),
                gps.getLongitude(),
                config.latitude,
                config.longitude,
                distanceResult
        );

        float distance = distanceResult[0];
        if (!Float.isFinite(distance)) {
            return GeofenceDecision.block("PROJECT AREA CHECK FAILED");
        }

        String distanceText = formatMeters(distance);
        String radiusText = formatMeters(config.radiusMeters);

        if (distance > config.radiusMeters) {
            return GeofenceDecision.block(
                    "OUTSIDE AREA • " + distanceText + " / " + radiusText
            );
        }

        return GeofenceDecision.allow(
                "READY • AREA " + distanceText + " / " + radiusText
        );
    }

    private Location getFreshGpsLocation() {
        try {
            LocationManager lm = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return null;

            Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps == null) return null;

            long ageMs = Math.abs(System.currentTimeMillis() - gps.getTime());
            if (ageMs > GEOFENCE_GPS_MAX_AGE_MS) return null;

            return gps;
        } catch (SecurityException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatMeters(double meters) {
        if (!Double.isFinite(meters)) return "--";
        if (meters < 1000d) return Math.round(meters) + "m";
        return String.format(Locale.US, "%.1fkm", meters / 1000d);
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

    private static final class GeofenceDecision {
        final boolean allowed;
        final String message;

        private GeofenceDecision(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }

        static GeofenceDecision notApplicable() {
            return new GeofenceDecision(true, null);
        }

        static GeofenceDecision allow(String message) {
            return new GeofenceDecision(true, message);
        }

        static GeofenceDecision block(String message) {
            return new GeofenceDecision(false, message);
        }
    }
}
