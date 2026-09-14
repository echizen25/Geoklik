package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import ph.gov.geocamera.core.utils.CameraPrefs;

/**
 * Read-only camera flash indicator.
 *
 * Flash selection now lives in the main Settings module. This view removes
 * itself from the crowded right-side camera controls and becomes a small
 * overlay indicator. OFF is intentionally hidden; AUTO and ON remain visible
 * so the field user knows that flash may fire.
 */
public final class FlashModeButton extends AppCompatTextView {

    private CameraPrefs prefs;
    private boolean movedToOverlay = false;

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
        setMaxLines(1);
        setClickable(false);
        setFocusable(false);
        setPadding(dp(9), dp(5), dp(9), dp(5));
        setBackground(makeIndicatorBackground());
        setElevation(dp(10));
        refreshIndicator();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshIndicator();
        if (!movedToOverlay) post(this::moveOutOfControlPanel);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) refreshIndicator();
    }

    private void moveOutOfControlPanel() {
        if (movedToOverlay) return;
        FrameLayout root = findRootFrame(this);
        ViewGroup parent = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
        if (root == null || parent == null || parent == root) {
            refreshIndicator();
            return;
        }

        try {
            // Set first because addView() immediately triggers another attach callback.
            movedToOverlay = true;
            parent.removeView(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END
            );
            lp.topMargin = dp(78);
            lp.rightMargin = dp(14);
            root.addView(this, lp);
            bringToFront();
            refreshIndicator();
        } catch (Exception ignored) {
            movedToOverlay = false;
        }
    }

    private void refreshIndicator() {
        if (prefs == null) return;
        String mode = prefs.getFlashMode();
        if (CameraPrefs.FLASH_OFF.equals(mode)) {
            setText("");
            setContentDescription("Flash Off");
            setVisibility(View.GONE);
        } else if (CameraPrefs.FLASH_ON.equals(mode)) {
            setText("⚡ ON");
            setContentDescription("Flash On");
            setVisibility(View.VISIBLE);
        } else {
            setText("⚡ AUTO");
            setContentDescription("Flash Automatic");
            setVisibility(View.VISIBLE);
        }
    }

    private GradientDrawable makeIndicatorBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(175, 0, 0, 0));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.argb(90, 255, 255, 255));
        return bg;
    }

    private FrameLayout findRootFrame(View view) {
        android.view.ViewParent parent = view.getParent();
        FrameLayout candidate = null;
        while (parent instanceof View) {
            if (parent instanceof FrameLayout) candidate = (FrameLayout) parent;
            parent = parent.getParent();
        }
        return candidate;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
