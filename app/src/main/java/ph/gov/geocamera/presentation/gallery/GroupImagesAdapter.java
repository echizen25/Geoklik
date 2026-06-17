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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ph.gov.geocamera.R;

public class GroupImagesAdapter extends RecyclerView.Adapter<GroupImagesAdapter.VH> {

    public interface Callback {
        void onImageClicked(String groupId, String clickedUuid); // ✅ String now
        void onImageLongPressed(String uuid);
        void onSelectionCountChanged(int count);
    }

    public static class ImageItem {
        public String uuid;
        public String filename;
        public String timestamp;  // "yyyy-MM-dd HH:mm:ss"
        public int status;        // 0=pending, !=0 synced
    }

    private final Context context;
    private final Callback callback;
    private final String groupId;   // ✅ String UUID
    private int thumbPx;

    private final List<ImageItem> items = new ArrayList<>();
    private final Map<String, String> uuidToPath = new HashMap<>();

    // selection
    private boolean selectionMode = false;
    private final LinkedHashSet<String> selectedUuids = new LinkedHashSet<>();

    // If you want to hide dates in grid, set this to false
    private final boolean showMetaLine = false;

    private final SimpleDateFormat dbSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat uiSdf = new SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault());

    public GroupImagesAdapter(Context ctx, Callback cb, String groupId, int spanCount) {
        context = ctx;
        callback = cb;
        this.groupId = (groupId == null) ? "" : groupId.trim();

        setHasStableIds(true);

        // estimate thumb size based on screen width / span
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int screenPx = dm.widthPixels;

        int rvPadding = dp(8) * 2;
        int spacing = dp(8);
        int totalSpacing = spacing * (spanCount + 1); // include edges
        int available = screenPx - rvPadding - totalSpacing;

        int itemPx = Math.max(dp(90), available / Math.max(2, spanCount));
        thumbPx = itemPx;
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

        // Keep selection only if uuid still exists
        selectedUuids.retainAll(uuidToPath.keySet());
        notifyDataSetChanged();

        if (callback != null) callback.onSelectionCountChanged(selectedUuids.size());
    }

    @Override
    public long getItemId(int position) {
        String u = items.get(position).uuid;
        return (u == null) ? position : u.hashCode();
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

        // thumb
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

        // badge
        boolean synced = (it.status != 0);
        if (synced) {
            h.tvBadge.setText("SYNCED");
            h.tvBadge.setBackgroundResource(R.drawable.bg_badge_synced);
        } else {
            h.tvBadge.setText("PENDING");
            h.tvBadge.setBackgroundResource(R.drawable.bg_badge_pending);
        }
        h.tvBadge.setVisibility(View.VISIBLE);

        // labels
        h.tvFileName.setText(shortNameFromPath(it.filename));

        if (showMetaLine) {
            h.tvCapturedAt.setVisibility(View.VISIBLE);
            h.tvCapturedAt.setText(formatCapturedAt(it.timestamp));
        } else {
            h.tvCapturedAt.setVisibility(View.GONE);
        }

        // selection UI
        boolean isSelected = it.uuid != null && selectedUuids.contains(it.uuid);
        h.selectionOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        View.OnClickListener click = v -> {
            if (it.uuid == null) return;

            if (selectionMode) {
                toggleSelection(it.uuid);
            } else {
                if (callback != null) callback.onImageClicked(groupId, it.uuid); // ✅ pass String groupId
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
    public void updateSpanCount(int spanCount) {
        if (spanCount <= 0) return;

        // recompute thumb size based on new span
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenPx = dm.widthPixels;

        int rvPadding = dp(8) * 2;
        int spacing = dp(4); // match spacing in Activity
        int totalSpacing = spacing * (spanCount + 1);
        int available = screenPx - rvPadding - totalSpacing;

        int itemPx = Math.max(dp(90), available / Math.max(2, spanCount));
        thumbPx = itemPx;

        notifyDataSetChanged();
    }
    @Override
    public int getItemCount() {
        return items.size();
    }

    // ========= Selection helpers =========

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

    // ========= formatting =========

    private String shortNameFromPath(String path) {
        if (path == null) return "";
        try {
            int idx = path.lastIndexOf(File.separator);
            if (idx >= 0 && idx < path.length() - 1) return path.substring(idx + 1);
            return path;
        } catch (Exception e) {
            return path;
        }
    }

    private String formatCapturedAt(String dbTimestamp) {
        if (dbTimestamp == null || dbTimestamp.trim().isEmpty()) return "";
        try {
            Date d = dbSdf.parse(dbTimestamp);
            if (d == null) return dbTimestamp;
            return uiSdf.format(d);
        } catch (ParseException e) {
            return dbTimestamp;
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, context.getResources().getDisplayMetrics()
        ));
    }

    static class VH extends RecyclerView.ViewHolder {
        MaterialCardView card;
        ShapeableImageView img;
        TextView tvFileName, tvCapturedAt, tvBadge;
        View selectionOverlay;

        VH(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card);
            img = itemView.findViewById(R.id.imgThumb);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvCapturedAt = itemView.findViewById(R.id.tvCapturedAt);
            tvBadge = itemView.findViewById(R.id.tvBadge);
            selectionOverlay = itemView.findViewById(R.id.viewSelectionOverlay);
        }
    }
}