package ph.gov.geocamera.presentation.gallery;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import ph.gov.geocamera.R;

/**
 * RecyclerView used by gallery screens that automatically swaps itself with
 * the matching empty-state view whenever the adapter becomes empty.
 *
 * This keeps empty-state behavior in the presentation layer and avoids
 * changing repository, upload, sync, or database logic.
 */
public class EmptyAwareRecyclerView extends RecyclerView {

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
            } else if (getId() == R.id.rvDates) {
                emptyView = root.findViewById(R.id.datesEmptyState);
            }
        }

        setVisibility(empty ? View.GONE : View.VISIBLE);
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
    }
}
