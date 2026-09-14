package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;

/**
 * Camera settings entry point. Keeps the live camera UI uncluttered while
 * exposing the existing project/GPS settings and the persisted flash mode.
 */
public class CameraSettingsButton extends AppCompatImageButton {

    private OnClickListener activityListener;
    private CameraPrefs prefs;

    public CameraSettingsButton(@NonNull Context context) {
        super(context);
        init(context);
    }

    public CameraSettingsButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CameraSettingsButton(@NonNull Context context,
                                @Nullable AttributeSet attrs,
                                int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        prefs = new CameraPrefs(context.getApplicationContext());
        super.setOnClickListener(v -> showCameraSettings());
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        activityListener = l;
    }

    private void showCameraSettings() {
        String mode = prettyFlashMode(prefs.getFlashMode());
        final String[] items = {
                "Project & GPS Settings",
                "Camera Flash: " + mode
        };

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Camera Settings")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        if (activityListener != null) activityListener.onClick(this);
                    } else if (which == 1) {
                        showFlashDialog();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
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
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    String selected = values[which];
                    CameraFlashController.setMode(getContext(), selected);
                    refreshIndicator();
                    dialog.dismiss();

                    if (!CameraPrefs.FLASH_OFF.equals(selected)
                            && !CameraFlashController.hasFlashUnit()) {
                        Toast.makeText(getContext(),
                                "This camera does not report a hardware flash unit.",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(),
                                "Flash: " + labels[which],
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshIndicator() {
        View root = getRootView();
        if (root == null) return;
        View indicator = root.findViewById(R.id.tvFlashIndicator);
        if (indicator instanceof FlashStatusIndicator) {
            ((FlashStatusIndicator) indicator).refresh();
        }
    }

    private static String prettyFlashMode(String mode) {
        if (CameraPrefs.FLASH_ON.equals(mode)) return "On";
        if (CameraPrefs.FLASH_OFF.equals(mode)) return "Off";
        return "Automatic";
    }
}
