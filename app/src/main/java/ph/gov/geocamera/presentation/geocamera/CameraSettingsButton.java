package ph.gov.geocamera.presentation.geocamera;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.presentation.site.SetSiteActivity;

/**
 * Single-window camera settings entry point.
 *
 * Keeps the live camera controls uncluttered while exposing the three field
 * settings people need most often: Change Project/Site, GPS mode and flash.
 * GPS and flash are changed directly inside the same dialog (no second menu).
 */
public class CameraSettingsButton extends AppCompatImageButton {

    // Retained because GeoCameraActivity still assigns a listener. The settings
    // view now owns the complete one-window flow instead of opening nested menus.
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
        // GeoCameraActivity still supplies its historical nested-menu listener.
        // Keep it for binary/source compatibility, but the button deliberately
        // handles all camera settings itself now.
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
                // SetSiteActivity already persists the normal camera selection.
                // Recreate once on return so GeoCamera reloads its active project
                // and overlay through its existing initialization path.
                if (hostActivity != null && !hostActivity.isFinishing()) {
                    hostActivity.recreate();
                }
            }
        });
    }

    private void showCameraSettings() {
        final Context context = getContext();
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(6));

        TextView hint = new TextView(context);
        hint.setText("Project, GPS and flash controls in one place.");
        hint.setTextSize(13f);
        hint.setAlpha(0.78f);
        hint.setPadding(0, 0, 0, dp(12));
        content.addView(hint, matchWrap());

        MaterialButton projectButton = makeSettingButton(
                "Change Project / Site",
                "Choose the Infrastructure, Activity or Personal capture target"
        );
        content.addView(projectButton, matchWrapWithTop(0));

        MaterialButton gpsButton = makeSettingButton(gpsTitle(), gpsSubtitle());
        content.addView(gpsButton, matchWrapWithTop(8));

        MaterialButton flashButton = makeSettingButton(flashTitle(), flashSubtitle());
        content.addView(flashButton, matchWrapWithTop(8));

        final boolean[] gpsChanged = {false};

        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle("Camera Settings")
                .setView(content)
                .setPositiveButton("Done", null)
                .create();

        projectButton.setOnClickListener(v -> {
            dialog.dismiss();
            openProjectPicker();
        });

        gpsButton.setOnClickListener(v -> {
            boolean enableIndoor = !prefs.isIndoorAssistEnabled();
            prefs.saveIndoorAssistEnabled(enableIndoor);
            gpsChanged[0] = true;
            gpsButton.setText(twoLineLabel(gpsTitle(), gpsSubtitle()));
            Toast.makeText(context,
                    enableIndoor ? "GPS mode: Indoor Assist" : "GPS mode: Outdoor (GPS Only)",
                    Toast.LENGTH_SHORT).show();
        });

        flashButton.setOnClickListener(v -> {
            String next = nextFlashMode(prefs.getFlashMode());
            CameraFlashController.setMode(context, next);
            flashButton.setText(twoLineLabel(flashTitle(), flashSubtitle()));
            refreshIndicator();

            if (!CameraPrefs.FLASH_OFF.equals(next) && !CameraFlashController.hasFlashUnit()) {
                Toast.makeText(context,
                        "This camera does not report a hardware flash unit.",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Flash: " + prettyFlashMode(next), Toast.LENGTH_SHORT).show();
            }
        });

        dialog.setOnDismissListener(d -> {
            if (gpsChanged[0] && !awaitingProjectPicker
                    && hostActivity != null && !hostActivity.isFinishing()) {
                // GeoCamera caches the Indoor Assist flag when it starts. A single
                // recreation applies the new GPS mode without adding more dialogs.
                hostActivity.recreate();
            }
        });

        dialog.show();
    }

    private void openProjectPicker() {
        if (hostActivity == null) {
            // Very defensive fallback for preview/non-Activity contexts.
            if (activityListener != null) activityListener.onClick(this);
            return;
        }
        awaitingProjectPicker = true;
        hostActivity.startActivity(new Intent(hostActivity, SetSiteActivity.class));
    }

    private MaterialButton makeSettingButton(String title, String subtitle) {
        MaterialButton button = new MaterialButton(getContext());
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        button.setTextSize(14f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(60));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setCornerRadius(dp(14));
        button.setText(twoLineLabel(title, subtitle));
        return button;
    }

    private CharSequence twoLineLabel(String title, String subtitle) {
        return title + "\n" + subtitle;
    }

    private String gpsTitle() {
        return prefs.isIndoorAssistEnabled()
                ? "GPS Mode  •  INDOOR ASSIST"
                : "GPS Mode  •  OUTDOOR";
    }

    private String gpsSubtitle() {
        return prefs.isIndoorAssistEnabled()
                ? "Assisted/network location allowed up to the existing indoor limit"
                : "GPS-only field capture with the normal accuracy and satellite rules";
    }

    private String flashTitle() {
        return "Camera Flash  •  " + prettyFlashMode(prefs.getFlashMode()).toUpperCase();
    }

    private String flashSubtitle() {
        return "Tap to cycle Automatic → On → Off";
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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(topDp);
        return lp;
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
