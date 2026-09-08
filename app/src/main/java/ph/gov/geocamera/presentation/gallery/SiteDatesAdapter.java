package ph.gov.geocamera.presentation.gallery;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.R;

public class SiteDatesAdapter extends RecyclerView.Adapter<SiteDatesAdapter.VH> {

    private static final String TYPE_PROJECT_ACTIVITY = "PROJECT_ACTIVITY";
    private static final String TYPE_PERSONAL = "PERSONAL";

    public static class DateItem {
        public String sessionDate;
        public String groupId;
        public int totalPhotos;
        public int uploadedPhotos;
        public int uploadingPhotos;
        public int failedPhotos;
        public int pendingPhotos;
        public int unsyncedPhotos;
        public String latestFilename;
        public String latestTimestamp;
        public String remarks;
    }

    public interface OnClick {
        void onClick(DateItem item);
        void onRemarksClick(DateItem item);
    }

    private final Context context;
    private final String captureType;
    private final OnClick onClick;
    private final List<DateItem> items = new ArrayList<>();

    private final SimpleDateFormat sdfDb = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat sdfUi = new SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault());

    public SiteDatesAdapter(Context context, String captureType, OnClick onClick) {
        this.context = context;
        this.captureType = normalizeType(captureType);
        this.onClick = onClick;
        setHasStableIds(true);
    }

    public void submit(List<DateItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        String key = items.get(position).groupId;
        if (key == null || key.trim().isEmpty()) key = items.get(position).sessionDate;
        return key == null ? position : key.hashCode();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_date_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DateItem it = items.get(position);

        h.tvDate.setText(prettyDate(it.sessionDate));

        int total = Math.max(0, it.totalPhotos);
        h.tvPhotoCount.setText(total + " photo" + (total == 1 ? "" : "s"));

        bindStatus(h, it, total);
        bindRemarks(h, it);
        bindThumbnail(h, it);

        h.btnRemarks.setOnClickListener(v -> {
            if (onClick != null) onClick.onRemarksClick(it);
        });

        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClick(it);
        });
    }

    private void bindStatus(@NonNull VH h, @NonNull DateItem it, int total) {
        String status;
        int color;

        if (TYPE_PERSONAL.equals(captureType)) {
            status = total <= 0 ? "NO PHOTOS" : "ON DEVICE";
            color = Color.parseColor("#546E7A");
        } else {
            boolean localOnly = total > 0
                    && it.unsyncedPhotos > 0
                    && it.pendingPhotos == 0
                    && it.uploadingPhotos == 0
                    && it.failedPhotos == 0;

            if (total <= 0) {
                status = "NO PHOTOS";
                color = ContextCompat.getColor(context, android.R.color.darker_gray);
            } else if (it.uploadingPhotos > 0) {
                status = "UPLOADING";
                color = Color.parseColor("#F57C00");
            } else if (it.failedPhotos > 0) {
                status = "FAILED";
                color = Color.parseColor("#D32F2F");
            } else if (localOnly) {
                status = "LOCAL";
                color = Color.parseColor("#546E7A");
            } else if (it.unsyncedPhotos > 0 || it.pendingPhotos > 0) {
                status = "PENDING";
                color = Color.parseColor("#004B24");
            } else {
                status = "SYNCED";
                color = Color.parseColor("#2E7D32");
            }
        }

        h.tvSyncStatus.setText("Status: " + status);
        h.tvSyncStatus.setTextColor(color);
    }

    private void bindRemarks(@NonNull VH h, @NonNull DateItem it) {
        String r = it.remarks == null ? "" : it.remarks.trim();

        if (!r.isEmpty()) {
            h.tvRemarks.setVisibility(View.VISIBLE);
            if (TYPE_PROJECT_ACTIVITY.equals(captureType)) {
                h.tvRemarks.setText("Album: " + r);
            } else if (TYPE_PERSONAL.equals(captureType)) {
                h.tvRemarks.setText("Note: " + r);
            } else {
                h.tvRemarks.setText("Remarks: " + r);
            }
            h.btnRemarks.setText("Edit Note");
        } else {
            h.tvRemarks.setVisibility(View.GONE);
            h.tvRemarks.setText("");
            h.btnRemarks.setText("Add Note");
        }
    }

    private void bindThumbnail(@NonNull VH h, @NonNull DateItem it) {
        String path = it.latestFilename == null ? "" : it.latestFilename.trim();

        if (!path.isEmpty() && new File(path).exists()) {
            Glide.with(context)
                    .load(new File(path))
                    .thumbnail(0.25f)
                    .centerCrop()
                    .placeholder(R.drawable.ph_shimer_tiny)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(h.imgLatest);
        } else {
            h.imgLatest.setImageResource(android.R.drawable.ic_menu_gallery);
        }
    }

    private String prettyDate(String yyyyMmDd) {
        if (yyyyMmDd == null || yyyyMmDd.trim().isEmpty()) return "Unknown date";
        try {
            Date d = sdfDb.parse(yyyyMmDd.trim());
            return d == null ? yyyyMmDd : sdfUi.format(d);
        } catch (ParseException e) {
            return yyyyMmDd;
        }
    }

    private static String normalizeType(String type) {
        if (type == null) return "INFRA";
        String value = type.trim().toUpperCase(Locale.US);
        if (TYPE_PROJECT_ACTIVITY.equals(value)) return TYPE_PROJECT_ACTIVITY;
        if (TYPE_PERSONAL.equals(value)) return TYPE_PERSONAL;
        return "INFRA";
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ShapeableImageView imgLatest;
        TextView tvDate, tvPhotoCount, tvSyncStatus, tvRemarks;
        MaterialButton btnRemarks;

        VH(@NonNull View v) {
            super(v);
            imgLatest = v.findViewById(R.id.imgDateThumb);
            tvDate = v.findViewById(R.id.tvDateTitle);
            tvPhotoCount = v.findViewById(R.id.tvPhotoCount);
            tvSyncStatus = v.findViewById(R.id.tvSyncStatus);
            tvRemarks = v.findViewById(R.id.tvRemarks);
            btnRemarks = v.findViewById(R.id.btnRemarks);
        }
    }
}
