package ph.gov.geocamera.presentation.gallery;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.chrisbanes.photoview.PhotoView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ph.gov.geocamera.R;

public class PreviewPagerAdapter extends RecyclerView.Adapter<PreviewPagerAdapter.VH> {

    public interface Callback {
        void onPhotoLongPressed(String uuid);
        void onPhotoTapped(); // optional (pwede mo pang fullscreen later)
    }

    public static class PhotoItem {
        public String uuid;
        public String filename;
        public String timestamp;
        public int status;
    }

    private final Context context;
    private final Callback callback;

    private final List<PhotoItem> items = new ArrayList<>();
    private Set<String> selected = new HashSet<>();

    public PreviewPagerAdapter(Context ctx, Callback cb) {
        context = ctx;
        callback = cb;
        setHasStableIds(true);
    }

    public void submit(List<PhotoItem> data, Set<String> selectedUuids) {
        items.clear();
        if (data != null) items.addAll(data);

        selected = (selectedUuids == null) ? new HashSet<>() : selectedUuids;
        notifyDataSetChanged();
    }

    public void setSelection(Set<String> selectedUuids) {
        selected = (selectedUuids == null) ? new HashSet<>() : selectedUuids;
        notifyDataSetChanged();
    }

    public PhotoItem getItem(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        String u = items.get(position).uuid;
        return (u == null) ? position : u.hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_preview_page, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PhotoItem it = items.get(position);

        // load full image (still cached)
        if (it.filename != null && !it.filename.trim().isEmpty()) {
            Glide.with(context)
                    .load(new File(it.filename))
                    .fitCenter()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(h.photoView);
        } else {
            h.photoView.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        boolean isSelected = it.uuid != null && selected.contains(it.uuid);
        h.selectionOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        // tap
        h.photoView.setOnClickListener(v -> {
            if (callback != null) callback.onPhotoTapped();
        });

        // long press toggles selection
        h.photoView.setOnLongClickListener(v -> {
            if (callback != null && it.uuid != null) callback.onPhotoLongPressed(it.uuid);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        PhotoView photoView;
        View selectionOverlay;

        VH(@NonNull View itemView) {
            super(itemView);
            photoView = itemView.findViewById(R.id.photoView);
            selectionOverlay = itemView.findViewById(R.id.selectionOverlay);
        }
    }
}