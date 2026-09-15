package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.hardware.camera2.CaptureRequest;

import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;

import java.lang.ref.WeakReference;

import ph.gov.geocamera.core.utils.CameraPrefs;

/**
 * Applies the user's persisted still-photo flash preference to the active
 * CameraX back camera without changing GeoKlik's capture, GPS, watermark or
 * synchronization pipeline.
 *
 * AUTO uses Android camera auto-flash metering, ON requests flash for captures,
 * and OFF disables flash. Unsupported/no-flash devices fail safely to OFF.
 */
@ExperimentalCamera2Interop
public final class CameraFlashController {

    private static WeakReference<Camera> cameraRef = new WeakReference<>(null);
    private static WeakReference<Context> contextRef = new WeakReference<>(null);

    private CameraFlashController() {}

    public static void attach(@NonNull Context context, @NonNull Camera camera) {
        cameraRef = new WeakReference<>(camera);
        contextRef = new WeakReference<>(context.getApplicationContext());
        applySavedMode();
    }

    public static boolean hasFlashUnit() {
        Camera camera = cameraRef.get();
        try {
            return camera != null && camera.getCameraInfo().hasFlashUnit();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String getMode(@NonNull Context context) {
        return new CameraPrefs(context.getApplicationContext()).getFlashMode();
    }

    public static void setMode(@NonNull Context context, String mode) {
        CameraPrefs prefs = new CameraPrefs(context.getApplicationContext());
        prefs.saveFlashMode(mode);
        contextRef = new WeakReference<>(context.getApplicationContext());
        applyMode(prefs.getFlashMode());
    }

    private static void applySavedMode() {
        Context context = contextRef.get();
        if (context == null) return;
        applyMode(new CameraPrefs(context).getFlashMode());
    }

    private static void applyMode(String mode) {
        Camera camera = cameraRef.get();
        if (camera == null) return;

        try {
            Camera2CameraControl control = Camera2CameraControl.from(camera.getCameraControl());
            CaptureRequestOptions.Builder options = new CaptureRequestOptions.Builder();

            if (!camera.getCameraInfo().hasFlashUnit()
                    || CameraPrefs.FLASH_OFF.equals(mode)) {
                options.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON
                );
            } else if (CameraPrefs.FLASH_ON.equals(mode)) {
                options.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                );
            } else {
                options.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                );
            }

            control.setCaptureRequestOptions(options.build());
        } catch (Exception ignored) {
            // Flash support varies by device/camera implementation. Never let a
            // flash-control failure interfere with GeoKlik photo capture.
        }
    }
}
