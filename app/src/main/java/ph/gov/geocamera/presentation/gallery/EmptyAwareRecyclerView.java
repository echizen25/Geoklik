package ph.gov.geocamera.presentation.gallery;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import ph.gov.geocamera.R;
import ph.gov.geocamera.presentation.geocamera.GeoCameraActivity;

/**
 * RecyclerView used by gallery screens that automatically swaps itself with
 * the matching empty-state view whenever the adapter becomes empty.
 *
 * This keeps empty-state behavior in the presentation layer and avoids
 * changing repository, upload, sync, or database logic.
 */
public class EmptyAwareRecyclerView extends RecyclerView {

    private Boolean lastEmpty = null;

    private final AdapterDataObserver observer = new AdapterDataObserver() {
        @Override public void onChanged() { updateEmptyState(); }
        @Override public void onItemRangeChanged(int positionStart, int itemCount) { updateEmptyState(); }
        @Override public void onItemRangeChanged(int positionStart, int itemCount, @Nullable Object payload) { updateEmptyState(); }
        @Override public void onItemRangeInserted(int positionStart, int itemCount) { updateEmptyState(); }
        @Override public void onItemRangeRemoved(int positionStart, int itemCount) { updateEmptyState(); }
        @Override public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) { updateEmptyState(); }
    };

    public EmptyAwareRecyclerView(@NonNull Context context) {
        super(context);
    }

    public EmptyAwareRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public EmptyAwareRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setAdapter(@Nullable Adapter adapter) {
        Adapter old = getAdapter();
        if (old != null) {
            try { old.unregisterAdapterDataObserver(observer); }
            catch (Exception ignored) {}
        }

        super.setAdapter(adapter);

        if (adapter != null) {
            adapter.registerAdapterDataObserver(observer);
        }
        post(this::updateEmptyState);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::updateEmptyState);
    }

    private void updateEmptyState() {
        Adapter adapter = getAdapter();
        boolean empty = adapter == null || adapter.getItemCount() == 0;

        View emptyView = null;
        View root = getRootView();
        if (root != null) {
            if (getId() == R.id.rvGallery) {
                emptyView = root.findViewById(R.id.galleryEmptyState);
                setupFirstCaptureAction(root);
            } else if (getId() == R.id.rvDates) {
                emptyView = root.findViewById(R.id.datesEmptyState);
            }
        }

        boolean changed = lastEmpty == null || lastEmpty != empty;

        if (empty) {
            animate().cancel();
            setVisibility(View.GONE);
            setAlpha(1f);

            if (emptyView != null) {
                emptyView.animate().cancel();
                emptyView.setVisibility(View.VISIBLE);
                if (changed) {
                    emptyView.setAlpha(0f);
                    emptyView.setScaleX(0.98f);
                    emptyView.setScaleY(0.98f);
                    emptyView.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(190)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                } else {
                    emptyView.setAlpha(1f);
                    emptyView.setScaleX(1f);
                    emptyView.setScaleY(1f);
                }
            }
        } else {
            if (emptyView != null) {
                emptyView.animate().cancel();
                emptyView.setVisibility(View.GONE);
                emptyView.setAlpha(1f);
                emptyView.setScaleX(1f);
                emptyView.setScaleY(1f);
            }

            animate().cancel();
            if (getVisibility() != View.VISIBLE || changed) {
                setVisibility(View.VISIBLE);
                setAlpha(changed ? 0f : 1f);
                animate()
                        .alpha(1f)
                        .setDuration(160)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else {
                setVisibility(View.VISIBLE);
                setAlpha(1f);
            }
        }

        lastEmpty = empty;
    }

    private void setupFirstCaptureAction(@NonNull View root) {
        View button = root.findViewById(R.id.btnFirstCapture);
        if (button == null || button.hasOnClickListeners()) return;

        button.setOnClickListener(v -> {
            v.animate().cancel();
            v.animate()
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .alpha(0.88f)
                    .setDuration(90)
                    .withEndAction(() -> v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(110)
                            .withEndAction(() -> {
                                Intent intent = new Intent(getContext(), GeoCameraActivity.class);
                                getContext().startActivity(intent);
                            })
                            .start())
                    .start();
        });
    }
}
