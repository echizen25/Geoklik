package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

/**
 * Keeps the custom settings button compatible with GeoCameraActivity while the
 * project type is now derived automatically from the selected capture target.
 * The Activity owns the settings menu (Change Project + Indoor Assist).
 */
public class CameraSettingsButton extends AppCompatImageButton {

    private OnClickListener activityListener;

    public CameraSettingsButton(@NonNull Context context) {
        super(context);
        init();
    }

    public CameraSettingsButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CameraSettingsButton(@NonNull Context context,
                                @Nullable AttributeSet attrs,
                                int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        super.setOnClickListener(v -> {
            if (activityListener != null) activityListener.onClick(this);
        });
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        activityListener = l;
    }
}
