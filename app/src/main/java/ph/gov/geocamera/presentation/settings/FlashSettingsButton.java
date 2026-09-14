package ph.gov.geocamera.presentation.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.presentation.geocamera.CameraFlashController;

/** Settings-module control for the persisted AUTO / ON / OFF camera flash mode. */
public final class FlashSettingsButton extends MaterialButton {

    private CameraPrefs prefs;

    public FlashSettingsButton(@NonNull Context context) {
        super(context);
        init(context);
    }

    public FlashSettingsButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public FlashSettingsButton(@NonNull Context context,
                               @Nullable AttributeSet attrs,
                               int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        prefs = new CameraPrefs(context.getApplicationContext());
        setAllCaps(false);
        setOnClickListener(v -> showFlashDialog());
        refreshLabel();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshLabel();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) refreshLabel();
    }

    private void showFlashDialog() {
        final String[] labels = {"Automatic", "On", "Off"};
        final String[] values = {
                CameraPrefs.FLASH_AUTO,
                CameraPrefs.FLASH_ON,
                CameraPrefs.FLASH_OFF
        };

        String current = prefs.getFlashMode();
        int checked = CameraPrefs.FLASH_ON.equals(current)
                ? 1
                : (CameraPrefs.FLASH_OFF.equals(current) ? 2 : 0);

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Camera Flash")
                .setMessage("Choose how GeoKlik should use the rear camera flash during photo capture.")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    CameraFlashController.setMode(getContext(), values[which]);
                    refreshLabel();
                    dialog.dismiss();
                    Toast.makeText(getContext(), "Camera flash: " + labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshLabel() {
        if (prefs == null) return;
        String mode = prefs.getFlashMode();
        if (CameraPrefs.FLASH_ON.equals(mode)) {
            setText("Camera Flash  •  ON");
        } else if (CameraPrefs.FLASH_OFF.equals(mode)) {
            setText("Camera Flash  •  OFF");
        } else {
            setText("Camera Flash  •  AUTOMATIC");
        }
    }
}
