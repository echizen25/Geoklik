package ph.gov.geocamera.presentation.gallery;

import android.content.Context;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.imageview.ShapeableImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ph.gov.geocamera.R;

public class GroupImagesAdapter extends RecyclerView.Adapter<GroupImagesAdapter.VH> {

    public interface Callback {
        void onImageClicked(String groupId, String clickedUuid);
        void onImageLongPressed(String uuid);
        void onSelectionCountChanged(int count);
    }

    public static class ImageItem {
        public String uuid;
        public String filename;
        public String timestamp;
        public int status;
        public boolean savedToDevice;
    }

    private final Context context;
    private final Callback callback;
    private final String groupId;
    private int thumbPx;

    private final List<ImageItem> items = new ArrayList<>();
    private final Map<String, String> uuidToPath = new HashMap<>();

    private boolean selectionMode = false;
    private final LinkedHashSet<String> selectedUuids = new LinkedHashSet<>();

    public GroupImagesAdapter(Context ctx, Callback cb, String groupId, int spanCount) {
        context = ctx;
        callback = cb;
        this.groupId = groupId == null ? "" : groupId.trim();
        setHasStableIds(true);
        recomputeThumbSize(spanCount);
    }

    public void submit(List<ImageItem> data) {
        items.clear();
        uuidToPath.clear();

        if (data != null) {
            items.addAll(data);
            for (ImageItem it : data) {
                if (it != null && it.uuid != null) {
                    uuidToPath.put(it.uuid, it.filename);
                }
            }
        }

        selectedUuids.retainAll(uuidToPath.keySet());
        notifyDataSetChanged();
        if (callback != null) callback.onSelectionCountChanged(selectedUuids.size());
    }

    @Override
    public long getItemId(int position) {
        String u = items.get(position).uuid;
        return u == null ? position : u.hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_image_grid, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ImageItem it = items.get(position);
        if (it == null) return;

        if (it.filename != null && !it.filename.trim().isEmpty()) {
            Glide.with(context)
                    .load(new File(it.filename))
                    .override(thumbPx, thumbPx)
                    .centerCrop()
                    .thumbnail(0.25f)
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(R.drawable.ph_shimer_tiny)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(h.img);
        } else {
            h.img.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        bindStatusBadge(h.tvBadge, it.status);
        h.tvSaved.setVisibility(it.savedToDevice ? View.VISIBLE : View.GONE);

        boolean isSelected = it.uuid != null && selectedUuids.contains(it.uuid);
        h.selectionOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        h.card.setStrokeWidth(dp(isSelected ? 2 : 1));

        View.OnClickListener click = v -> {
            if (it.uuid == null) return;
            if (selectionMode) {
                toggleSelection(it.uuid);
            } else if (callback != null) {
                callback.onImageClicked(groupId, it.uuid);
            }
        };

        View.OnLongClickListener longClick = v -> {
            if (it.uuid == null) return true;
            if (callback != null) callback.onImageLongPressed(it.uuid);
            return true;
        };

        h.card.setOnClickListener(click);
        h.card.setOnLongClickListener(longClick);
        h.img.setOnClickListener(click);
        h.img.setOnLongClickListener(longClick);
    }

    private void bindStatusBadge(TextView badge, int status) {
        if (status == 1) {
            badge.setText("SYNCED");
            badge.setBackgroundResource(R.drawable.bg_gallery_badge_synced);
        } else if (status == 2) {
            badge.setText("FAILED");
            badge.setBackgroundResource(R.drawable.bg_gallery_badge_failed);
        } else if (status == 3) {
            badge.setText("UPLOADING");
            badge.setBackgroundResource(R.drawable.bg_gallery_badge_uploading);
        } else {
            badge.setText("PENDING");
            badge.setBackgroundResource(R.drawable.bg_gallery_badge_pending);
        }
        badge.setVisibility(View.VISIBLE);
    }

    public void updateSpanCount(int spanCount) {
        if (spanCount <= 0) return;
        recomputeThumbSize(spanCount);
        notifyDataSetChanged();
    }

    private void recomputeThumbSize(int spanCount) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenPx = dm.widthPixels;
        int rvPadding = dp(8) * 2;
        int spacing = dp(4);
        int totalSpacing = spacing * (spanCount + 1);
        int available = screenPx - rvPadding - totalSpacing;
        thumbPx = Math.max(dp(90), available / Math.max(2, spanCount));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        if (!enabled) {
            selectedUuids.clear();
            if (callback != null) callback.onSelectionCountChanged(0);
            notifyDataSetChanged();
        }
    }

    public void toggleSelection(String uuid) {
        if (uuid == null) return;
        if (selectedUuids.contains(uuid)) selectedUuids.remove(uuid);
        else selectedUuids.add(uuid);
        if (callback != null) callback.onSelectionCountChanged(selectedUuids.size());
        notifyDataSetChanged();
    }

    public void clearSelection() {
        selectedUuids.clear();
        if (callback != null) callback.onSelectionCountChanged(0);
        notifyDataSetChanged();
    }

    public void selectAll() {
        selectedUuids.clear();
        for (ImageItem it : items) {
            if (it != null && it.uuid != null) selectedUuids.add(it.uuid);
        }
        if (callback != null) callback.onSelectionCountChanged(selectedUuids.size());
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        return selectedUuids.size();
    }

    public Set<String> getSelectedUuids() {
        return new LinkedHashSet<>(selectedUuids);
    }

    public String getPathByUuid(String uuid) {
        return uuidToPath.get(uuid);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, context.getResources().getDisplayMetrics()
        ));
    }

    static class VH extends RecyclerView.ViewHolder {
        MaterialCardView card;
        ShapeableImageView img;
        TextView tvBadge, tvSaved;
        View selectionOverlay;

        VH(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card);
            img = itemView.findViewById(R.id.imgThumb);
            tvBadge = itemView.findViewById(R.id.tvBadge);
            tvSaved = itemView.findViewById(R.id.tvSaved);
            selectionOverlay = itemView.findViewById(R.id.viewSelectionOverlay);
        }
    }
}
