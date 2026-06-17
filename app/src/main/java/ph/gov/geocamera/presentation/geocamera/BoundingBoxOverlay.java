package ph.gov.geocamera.presentation.geocamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class BoundingBoxOverlay extends View {

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Box> boxes = new ArrayList<>();

    // Preview transform info
    private int imageWidth = 0;
    private int imageHeight = 0;
    private int rotationDegrees = 0;
    private boolean isFrontCamera = false;

    public static class Box {
        public final RectF rect;   // in image coords
        public final String label;
        public Box(RectF rect, String label) {
            this.rect = rect;
            this.label = label;
        }
    }

    public BoundingBoxOverlay(Context context) { super(context); init(); }
    public BoundingBoxOverlay(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public BoundingBoxOverlay(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(6f);

        textPaint.setTextSize(36f);
        textPaint.setStrokeWidth(3f);
    }

    public void setImageInfo(int width, int height, int rotationDegrees, boolean isFrontCamera) {
        this.imageWidth = width;
        this.imageHeight = height;
        this.rotationDegrees = rotationDegrees;
        this.isFrontCamera = isFrontCamera;
    }

    public void setBoxes(List<Box> newBoxes) {
        boxes.clear();
        if (newBoxes != null) boxes.addAll(newBoxes);
        postInvalidateOnAnimation();
    }

    public void clear() {
        boxes.clear();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (boxes.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return;

        // We map image coords -> view coords (center-crop-ish).
        // CameraX PreviewView uses transformations; this simple mapping works well if PreviewView is FILL_CENTER.
        float viewW = getWidth();
        float viewH = getHeight();

        int imgW = imageWidth;
        int imgH = imageHeight;

        // If rotated 90/270, swap
        boolean swap = rotationDegrees == 90 || rotationDegrees == 270;
        float srcW = swap ? imgH : imgW;
        float srcH = swap ? imgW : imgH;

        float scale = Math.max(viewW / srcW, viewH / srcH);
        float dx = (viewW - srcW * scale) / 2f;
        float dy = (viewH - srcH * scale) / 2f;

        for (Box b : boxes) {
            RectF r = new RectF(b.rect);

            // If rotated, rotate rect mapping (approx).
            RectF mapped = mapRect(r, imgW, imgH, rotationDegrees);

            // Mirror for front cam if needed
            if (isFrontCamera) {
                float left = mapped.left;
                float right = mapped.right;
                mapped.left = imgW - right;
                mapped.right = imgW - left;
            }

            // Now scale to view
            float left = mapped.left * scale + dx;
            float top = mapped.top * scale + dy;
            float right = mapped.right * scale + dx;
            float bottom = mapped.bottom * scale + dy;

            RectF out = new RectF(left, top, right, bottom);

            // Draw box
            boxPaint.setColor(0xFF00FF00); // green
            canvas.drawRoundRect(out, 18f, 18f, boxPaint);

            // Draw label
            if (b.label != null && !b.label.trim().isEmpty()) {
                textPaint.setColor(0xFF000000);
                canvas.drawText(b.label, out.left + 6, out.top - 10, textPaint);
            }
        }
    }

    // Very lightweight rect rotation mapping for common rotations
    private RectF mapRect(RectF r, int imgW, int imgH, int rot) {
        if (rot == 0) return r;

        if (rot == 90) {
            // (x,y) -> (h - y, x)
            return new RectF(
                    imgH - r.bottom,
                    r.left,
                    imgH - r.top,
                    r.right
            );
        }
        if (rot == 180) {
            return new RectF(
                    imgW - r.right,
                    imgH - r.bottom,
                    imgW - r.left,
                    imgH - r.top
            );
        }
        if (rot == 270) {
            return new RectF(
                    r.top,
                    imgW - r.right,
                    r.bottom,
                    imgW - r.left
            );
        }
        return r;
    }
}