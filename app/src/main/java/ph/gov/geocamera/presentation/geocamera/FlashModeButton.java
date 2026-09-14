package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ph.gov.geocamera.core.utils.CameraPrefs;

/** Compact camera control for AUTO / ON / OFF still-photo flash modes. */
public final class FlashModeButton extends AppCompatTextView {

    private CameraPrefs prefs;

    public FlashModeButton(@NonNull Context context) {
        super(context);
        init(context);
    }

    public FlashModeButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public FlashModeButton(@NonNull Context context,
                           @Nullable AttributeSet attrs,
                           int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        prefs = new CameraPrefs(context.getApplicationContext());
        setGravity(Gravity.CENTER);
        setTextColor(Color.WHITE);
        setTextSize(9f);
        setAllCaps(false);
        setMaxLines(2);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> showModeDialog());
        refreshLabel();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshLabel();
    }

    private void showModeDialog() {
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
                    refreshLabel();
                    dialog.dismiss();

                    if (!CameraFlashController.hasFlashUnit()) {
                        Toast.makeText(
                                getContext(),
                                "This camera does not report a hardware flash unit.",
                                Toast.LENGTH_SHORT
                        ).show();
                    } else {
                        Toast.makeText(
                                getContext(),
                                "Flash: " + labels[which],
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshLabel() {
        if (prefs == null) return;
        String mode = prefs.getFlashMode();
        if (CameraPrefs.FLASH_ON.equals(mode)) {
            setText("FLASH\nON");
            setContentDescription("Flash On");
        } else if (CameraPrefs.FLASH_OFF.equals(mode)) {
            setText("FLASH\nOFF");
            setContentDescription("Flash Off");
        } else {
            setText("FLASH\nAUTO");
            setContentDescription("Flash Automatic");
        }
    }
}
