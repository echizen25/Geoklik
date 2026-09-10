package ph.gov.geocamera.presentation.geocamera;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.ZoomState;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Adds field-friendly camera gestures without changing GeoKlik's capture pipeline:
 * pinch-to-zoom, a live zoom ratio indicator, and tap-to-focus with an animated ring.
 */
public final class CameraGestureController {

    private static final long FOCUS_FADE_DELAY_MS = 650L;

    private final Activity activity;
    private final PreviewView previewView;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final TextView zoomIndicator;
    private final FocusRingView focusRing;

    private Camera camera;
    private boolean scaling;

    public CameraGestureController(@NonNull Activity activity, @NonNull PreviewView previewView) {
        this.activity = activity;
        this.previewView = previewView;

        FrameLayout root = findRootFrame(previewView);
        zoomIndicator = createZoomIndicator();
        focusRing = new FocusRingView(activity);

        if (root != null) {
            root.addView(zoomIndicator);
            root.addView(focusRing);
        }

        scaleDetector = new ScaleGestureDetector(activity,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                        scaling = true;
                        return camera != null;
                    }

                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        applyPinchZoom(detector.getScaleFactor());
                        return true;
                    }

                    @Override
                    public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                        previewView.postDelayed(() -> scaling = false, 80L);
                    }
                });

        gestureDetector = new GestureDetector(activity,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(@NonNull MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        if (!scaling) focusAt(e.getX(), e.getY());
                        return true;
                    }
                });

        previewView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    public void attachCamera(@NonNull Camera camera) {
        this.camera = camera;

        if (activity instanceof LifecycleOwner) {
            camera.getCameraInfo().getZoomState().removeObservers((LifecycleOwner) activity);
            camera.getCameraInfo().getZoomState().observe((LifecycleOwner) activity,
                    this::renderZoomState);
        } else {
            ZoomState state = camera.getCameraInfo().getZoomState().getValue();
            renderZoomState(state);
        }
    }

    private void applyPinchZoom(float scaleFactor) {
        Camera currentCamera = camera;
        if (currentCamera == null) return;

        ZoomState state = currentCamera.getCameraInfo().getZoomState().getValue();
        if (state == null) return;

        float target = state.getZoomRatio() * scaleFactor;
        target = Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), target));
        currentCamera.getCameraControl().setZoomRatio(target);
    }

    private void focusAt(float x, float y) {
        Camera currentCamera = camera;
        if (currentCamera == null) return;

        showFocusRing(x, y, FocusRingView.STATE_FOCUSING);

        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build();

        ListenableFuture<FocusMeteringResult> future =
                currentCamera.getCameraControl().startFocusAndMetering(action);

        future.addListener(() -> {
            try {
                FocusMeteringResult result = future.get();
                focusRing.setState(result != null && result.isFocusSuccessful()
                        ? FocusRingView.STATE_SUCCESS
                        : FocusRingView.STATE_FAILED);
            } catch (Exception ignored) {
                focusRing.setState(FocusRingView.STATE_FAILED);
            }
            focusRing.removeCallbacks(focusRing.hideRunnable);
            focusRing.postDelayed(focusRing.hideRunnable, FOCUS_FADE_DELAY_MS);
        }, ContextCompat.getMainExecutor(activity));
    }

    private void renderZoomState(ZoomState state) {
        if (state == null) return;
        zoomIndicator.setText(formatZoom(state.getZoomRatio()));
        zoomIndicator.setVisibility(View.VISIBLE);
    }

    private String formatZoom(float ratio) {
        if (Math.abs(ratio - Math.round(ratio)) < 0.05f) {
            return String.format(Locale.US, "%.0f×", ratio);
        }
        return String.format(Locale.US, "%.1f×", ratio);
    }

    private void showFocusRing(float x, float y, int state) {
        focusRing.removeCallbacks(focusRing.hideRunnable);
        focusRing.setState(state);
        focusRing.setX(x - focusRing.getRingSizePx() / 2f);
        focusRing.setY(y - focusRing.getRingSizePx() / 2f);
        focusRing.setVisibility(View.VISIBLE);
        focusRing.setAlpha(0f);
        focusRing.setScaleX(1.45f);
        focusRing.setScaleY(1.45f);
        focusRing.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150L)
                .start();
    }

    private TextView createZoomIndicator() {
        TextView view = new TextView(activity);
        view.setText("1×");
        view.setTextColor(Color.WHITE);
        view.setTextSize(15f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(5), dp(12), dp(5));
        view.setBackground(createPillBackground());
        view.setClickable(false);
        view.setFocusable(false);
        view.setElevation(dp(12));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(14);
        view.setLayoutParams(lp);
        return view;
    }

    private android.graphics.drawable.GradientDrawable createPillBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(175, 0, 0, 0));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(90, 255, 255, 255));
        return bg;
    }

    private FrameLayout findRootFrame(View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent instanceof FrameLayout) return (FrameLayout) parent;
            parent = parent.getParent();
        }
        return null;
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private final class FocusRingView extends View {
        static final int STATE_FOCUSING = 0;
        static final int STATE_SUCCESS = 1;
        static final int STATE_FAILED = 2;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int ringSizePx = dp(72);
        private int state = STATE_FOCUSING;

        private final Runnable hideRunnable = () -> animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(220L)
                .withEndAction(() -> setVisibility(View.GONE))
                .start();

        FocusRingView(Activity context) {
            super(context);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ringSizePx, ringSizePx);
            setLayoutParams(lp);
            setVisibility(View.GONE);
            setClickable(false);
            setFocusable(false);
            setElevation(dp(13));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
        }

        int getRingSizePx() {
            return ringSizePx;
        }

        void setState(int state) {
            this.state = state;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);

            int color;
            if (state == STATE_SUCCESS) color = Color.rgb(102, 255, 153);
            else if (state == STATE_FAILED) color = Color.rgb(255, 183, 77);
            else color = Color.WHITE;

            paint.setColor(color);
            float c = getWidth() / 2f;
            float radius = getWidth() * 0.34f;
            canvas.drawCircle(c, c, radius, paint);

            float inner = radius * 0.22f;
            canvas.drawLine(c - inner, c, c + inner, c, paint);
            canvas.drawLine(c, c - inner, c, c + inner, paint);
        }
    }
}
