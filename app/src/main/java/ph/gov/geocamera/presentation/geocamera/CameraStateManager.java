package ph.gov.geocamera.presentation.geocamera;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

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

    private final ImageButton captureButton;
    private final TextView statusText;
    private State state = State.WAITING_FOR_GPS;

    CameraStateManager(ImageButton captureButton, TextView statusText) {
        this.captureButton = captureButton;
        this.statusText = statusText;
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

        boolean ready = next == State.READY;
        if (captureButton != null) {
            captureButton.setEnabled(ready);
            captureButton.setAlpha(ready ? 1f : 0.35f);
        }

        if (statusText != null) {
            statusText.setText(label(next));
            statusText.setVisibility(View.VISIBLE);
        }
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
}
