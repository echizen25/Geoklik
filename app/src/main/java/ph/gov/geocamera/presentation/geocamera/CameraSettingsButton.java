package ph.gov.geocamera.presentation.geocamera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.presentation.site.SetSiteActivity;

/**
 * Compact camera settings menu with exactly three options:
 * Change Project / Site, GPS mode, and Camera Flash.
 */
public class CameraSettingsButton extends AppCompatImageButton {

    private OnClickListener activityListener;
    private CameraPrefs prefs;
    private Activity hostActivity;
    private boolean awaitingProjectPicker = false;
    private boolean lifecycleObserverAdded = false;

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
        hostActivity = findActivity(context);
        super.setOnClickListener(v -> showCameraSettings());
        registerResumeRefreshIfPossible();
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        activityListener = l;
    }

    private void registerResumeRefreshIfPossible() {
        if (lifecycleObserverAdded || !(hostActivity instanceof LifecycleOwner)) return;
        lifecycleObserverAdded = true;
        ((LifecycleOwner) hostActivity).getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onResume(@NonNull LifecycleOwner owner) {
                if (!awaitingProjectPicker) return;
                awaitingProjectPicker = false;
                if (hostActivity != null && !hostActivity.isFinishing()) {
                    hostActivity.recreate();
                }
            }
        });
    }

    private void showCameraSettings() {
        final String[] items = new String[]{
                "Change Project / Site",
                prefs.isIndoorAssistEnabled()
                        ? "GPS Mode: INDOOR ASSIST"
                        : "GPS Mode: GPS ONLY",
                "Camera Flash: " + prettyFlashMode(prefs.getFlashMode()).toUpperCase()
        };

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Camera Settings")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        openProjectPicker();
                        return;
                    }

                    if (which == 1) {
                        boolean enableIndoor = !prefs.isIndoorAssistEnabled();
                        prefs.saveIndoorAssistEnabled(enableIndoor);
                        Toast.makeText(
                                getContext(),
                                enableIndoor
                                        ? "GPS Mode: Indoor Assist"
                                        : "GPS Mode: GPS Only",
                                Toast.LENGTH_SHORT
                        ).show();

                        if (hostActivity != null && !hostActivity.isFinishing()) {
                            hostActivity.recreate();
                        }
                        return;
                    }

                    if (which == 2) {
                        String next = nextFlashMode(prefs.getFlashMode());
                        CameraFlashController.setMode(getContext(), next);
                        refreshIndicator();

                        if (!CameraPrefs.FLASH_OFF.equals(next)
                                && !CameraFlashController.hasFlashUnit()) {
                            Toast.makeText(
                                    getContext(),
                                    "This camera does not report a hardware flash unit.",
                                    Toast.LENGTH_SHORT
                            ).show();
                        } else {
                            Toast.makeText(
                                    getContext(),
                                    "Flash: " + prettyFlashMode(next),
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void openProjectPicker() {
        if (hostActivity == null) {
            if (activityListener != null) activityListener.onClick(this);
            return;
        }

        awaitingProjectPicker = true;
        hostActivity.startActivity(new Intent(hostActivity, SetSiteActivity.class));
    }

    private void refreshIndicator() {
        View root = getRootView();
        if (root == null) return;
        View indicator = root.findViewById(R.id.tvFlashIndicator);
        if (indicator instanceof FlashStatusIndicator) {
            ((FlashStatusIndicator) indicator).refresh();
        }
    }

    private static String nextFlashMode(String current) {
        if (CameraPrefs.FLASH_AUTO.equals(current)) return CameraPrefs.FLASH_ON;
        if (CameraPrefs.FLASH_ON.equals(current)) return CameraPrefs.FLASH_OFF;
        return CameraPrefs.FLASH_AUTO;
    }

    private static String prettyFlashMode(String mode) {
        if (CameraPrefs.FLASH_ON.equals(mode)) return "On";
        if (CameraPrefs.FLASH_OFF.equals(mode)) return "Off";
        return "Automatic";
    }

    private Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }
}
