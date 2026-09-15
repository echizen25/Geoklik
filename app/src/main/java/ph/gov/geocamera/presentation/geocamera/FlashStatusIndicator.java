package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import ph.gov.geocamera.core.utils.CameraPrefs;

/** Small read-only camera indicator. Flash configuration lives under Camera Settings. */
public final class FlashStatusIndicator extends AppCompatTextView {

    private CameraPrefs prefs;

    public FlashStatusIndicator(@NonNull Context context) {
        super(context);
        init(context);
    }

    public FlashStatusIndicator(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public FlashStatusIndicator(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        prefs = new CameraPrefs(context.getApplicationContext());
        setClickable(false);
        setFocusable(false);
        refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) refresh();
    }

    public void refresh() {
        if (prefs == null) return;
        String mode = prefs.getFlashMode();
        if (CameraPrefs.FLASH_OFF.equals(mode)) {
            setVisibility(View.GONE);
            setText("");
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
}
