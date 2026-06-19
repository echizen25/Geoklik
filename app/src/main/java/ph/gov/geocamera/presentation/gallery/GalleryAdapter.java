package ph.gov.geocamera.presentation.gallery;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.util.ViewPreloadSizeProvider;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.presentation.geocamera.GeoCameraActivity;

public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.SiteVH>
        implements ListPreloader.PreloadModelProvider<File> {

    public interface Callback {
        void onSyncSiteClicked(String siteId, String year, boolean alreadySynced);
        void onSelectionChanged(int selectedCount);
        void onBulkSyncRequested(List<String> siteIds);
        String getSelectedYear();
    }

    private final Context context;
    private final ImageMetaRepository imageRepo;
    private final Callback callback;

    private final List<SiteItem> items = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();

    private boolean selectionMode = false;

    private final SimpleDateFormat dbSdf =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private final SimpleDateFormat uiSdf =
            new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

    private final ViewPreloadSizeProvider<File> preloadSizeProvider =
            new ViewPreloadSizeProvider<>();

    public GalleryAdapter(Context ctx, ImageMetaRepository img) {
        this.context = ctx;
        this.imageRepo = img;
        this.callback = (Callback) ctx;
        setHasStableIds(true);
    }

    public void loadSites(String project, String year, String search) {
        items.clear();

        Cursor c = null;
        try {
            c = imageRepo.getRootSiteCards(project, year, search);

            while (c != null && c.moveToNext()) {
                SiteItem item = new SiteItem();

                item.siteId = c.getString(0);
                item.totalPhotos = c.getInt(1);
                item.syncedPhotos = c.getInt(2);
                item.unsyncedPhotos = c.getInt(3);
                item.lastUpdated = c.getString(4);
                item.latestFilename = c.getString(5);
                item.latestTimestamp = c.getString(6);

                item.siteName = c.getString(7);
                item.project = c.getString(8);
                item.location = c.getString(9);

                item.pendingCount = safeInt(c, 10);
                item.uploadingCount = safeInt(c, 11);
                item.failedCount = safeInt(c, 12);
                item.projectCode = safeString(c, 13);      // tbl_projects.code
                item.beneficiary = safeString(c, 14);      // tbl_projects.beneficiary
                item.projectId = safeString(c, 15);        // tbl_projects.projectid
                item.coda = safeString(c, 16);             // tbl_projects.coda

                // ✅ detect if may NO_PROJECT_FOUND sa site na ito
                item.noProjectFoundCount = imageRepo.countFailedByErrorForSite(
                        item.siteId,
                        ImageMetaRepository.ERR_NO_PROJECT_FOUND
                );

                items.add(item);
            }
        } finally {
            if (c != null) c.close();
        }

        if (!selected.isEmpty()) {
            Set<String> keep = new HashSet<>();
            for (SiteItem it : items) {
                if (it.siteId != null) keep.add(it.siteId);
            }

            selected.retainAll(keep);

            if (selected.isEmpty()) {
                selectionMode = false;
            }

            callback.onSelectionChanged(selected.size());
        }

        notifyDataSetChanged();
    }

    public void loadSites(String project, String year) {
        loadSites(project, year, null);
    }

    public void clearSelection() {
        selectionMode = false;
        selected.clear();
        callback.onSelectionChanged(0);
        notifyDataSetChanged();
    }

    public List<String> getSelectedSiteIds() {
        return new ArrayList<>(selected);
    }

    public void bulkSyncSelected() {
        List<String> ids = getSelectedSiteIds();
        if (!ids.isEmpty()) {
            callback.onBulkSyncRequested(ids);
        }
    }

    private void toggleSelection(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) return;

        if (selected.contains(siteId)) {
            selected.remove(siteId);
        } else {
            selected.add(siteId);
        }

        if (selected.isEmpty()) {
            selectionMode = false;
        }

        callback.onSelectionChanged(selected.size());
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        String id = items.get(position).siteId;
        return (id == null) ? position : id.hashCode();
    }

    @NonNull
    @Override
    public SiteVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_site, parent, false);
        return new SiteVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SiteVH h, int position) {
        SiteItem item = items.get(position);
        preloadSizeProvider.setView(h.imgLatest);

        // Main title: show readable project code/display code first, not raw project/site id.
        String title = firstNonEmpty(
                item.projectCode,   // from ImageMetaRepository displayCode, usually p.code / s.code
                item.project,       // project label / coda / funding display
                item.siteName,      // site name if available
                item.siteId         // fallback only
        );

        h.tvSite.setText(safe(title, "SITE"));

        // Second line: show beneficiary from tbl_projects.
        String beneficiaryLine = firstNonEmpty(item.beneficiary);
        h.tvProjectLabel.setText("FCA: " + safe(beneficiaryLine, "—"));

        h.tvLocationLabel.setText("Location: " + safe(item.location, "-"));

        String dateText = formatMonthDayYearFromDb(item.latestTimestamp);
        h.tvMeta.setText(item.totalPhotos + " photos • " + dateText);

        bindUnsyncedBadge(h, item);
        bindStatusChip(h, item);
        bindThumbnail(h, item);

        h.btnGeoCamera.setOnClickListener(v -> {
            Intent i = new Intent(context, GeoCameraActivity.class);
            i.putExtra("siteId", item.siteId);
            context.startActivity(i);
        });

        h.card.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(item.siteId);
                return;
            }

            Intent i = new Intent(context, SiteDatesActivity.class);
            i.putExtra(SiteDatesActivity.EXTRA_SITE_ID, item.siteId);
            i.putExtra(SiteDatesActivity.EXTRA_YEAR, callback.getSelectedYear());
            context.startActivity(i);
        });

        h.card.setOnLongClickListener(v -> {
            selectionMode = true;
            toggleSelection(item.siteId);
            return true;
        });

        h.imgLatest.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(item.siteId);
                return;
            }

            showProjectDetails(item);
        });

        boolean isSelected = selected.contains(item.siteId);
        h.card.setChecked(isSelected);
        h.selectionOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
    }

    private void bindUnsyncedBadge(@NonNull SiteVH h, @NonNull SiteItem item) {
        if (item.uploadingCount > 0) {
            h.tvUnsyncedBadge.setVisibility(View.GONE);
            return;
        }

        if (item.unsyncedPhotos > 0) {
            h.tvUnsyncedBadge.setVisibility(View.VISIBLE);
            h.tvUnsyncedBadge.setText(item.unsyncedPhotos > 9 ? "9+" : String.valueOf(item.unsyncedPhotos));
            return;
        }

        h.tvUnsyncedBadge.setVisibility(View.GONE);
    }

    private void bindStatusChip(@NonNull SiteVH h, @NonNull SiteItem item) {
        h.tvStatusChip.setVisibility(View.VISIBLE);

        if (item.totalPhotos <= 0) {
            h.tvStatusChip.setText("EMPTY");
            h.tvStatusChip.setAlpha(0.75f);
            h.tvStatusChip.setTextColor(Color.GRAY);
            return;
        }

        if (item.uploadingCount > 0) {
            h.tvStatusChip.setText("UPLOADING");
            h.tvStatusChip.setAlpha(1f);
            h.tvStatusChip.setTextColor(Color.parseColor("#F57C00"));
            return;
        }

        if (item.noProjectFoundCount > 0) {
            h.tvStatusChip.setText("NO PROJECT");
            h.tvStatusChip.setAlpha(1f);
            h.tvStatusChip.setTextColor(Color.parseColor("#C62828"));
            return;
        }

        if (item.failedCount > 0) {
            h.tvStatusChip.setText("FAILED");
            h.tvStatusChip.setAlpha(1f);
            h.tvStatusChip.setTextColor(Color.RED);
            return;
        }

        if (item.pendingCount > 0) {
            h.tvStatusChip.setText("PENDING");
            h.tvStatusChip.setAlpha(1f);
            h.tvStatusChip.setTextColor(Color.parseColor("#004B24"));
            return;
        }

        h.tvStatusChip.setText("SYNCED");
        h.tvStatusChip.setAlpha(0.9f);
        h.tvStatusChip.setTextColor(Color.parseColor("#2E7D32"));
    }

    private void bindThumbnail(@NonNull SiteVH h, @NonNull SiteItem item) {
        if (item.latestFilename != null && !item.latestFilename.trim().isEmpty()) {
            Glide.with(context)
                    .load(new File(item.latestFilename))
                    .thumbnail(0.25f)
                    .centerCrop()
                    .placeholder(R.drawable.ph_shimer_tiny)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(h.imgLatest);
        } else {
            Glide.with(context).clear(h.imgLatest);
            h.imgLatest.setImageResource(android.R.drawable.ic_menu_gallery);
        }
    }


    private void showProjectDetails(@NonNull SiteItem item) {
        String status;

        if (item.uploadingCount > 0) status = "UPLOADING";
        else if (item.noProjectFoundCount > 0) status = "NO PROJECT";
        else if (item.failedCount > 0) status = "FAILED";
        else if (item.pendingCount > 0) status = "PENDING";
        else status = "SYNCED";

        String message =
                "Code: " + safe(item.projectCode, "—") + "\n" +
                        "Project ID: " + safe(item.projectId, "—") + "\n" +
                        "CODA: " + safe(item.coda, "—") + "\n" +
                        "Beneficiary: " + safe(item.beneficiary, "—") + "\n" +
                        "Site ID: " + safe(item.siteId, "—") + "\n" +
                        "Location: " + safe(item.location, "—") + "\n\n" +
                        "Total Photos: " + item.totalPhotos + "\n" +
                        "Synced: " + item.syncedPhotos + "\n" +
                        "Pending: " + item.pendingCount + "\n" +
                        "Uploading: " + item.uploadingCount + "\n" +
                        "Failed: " + item.failedCount + "\n" +
                        "Status: " + status + "\n\n" +
                        "Latest: " + formatMonthDayYearFromDb(item.latestTimestamp);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Project Details")
                .setMessage(message)
                .setPositiveButton("Open Photos", (d, w) -> {
                    Intent i = new Intent(context, SiteDatesActivity.class);
                    i.putExtra(SiteDatesActivity.EXTRA_SITE_ID, item.siteId);
                    i.putExtra(SiteDatesActivity.EXTRA_YEAR, callback.getSelectedYear());
                    context.startActivity(i);
                })
                .setNegativeButton("Close", null)
                .show();
    }


    private String formatMonthDayYearFromDb(String dbTimestamp) {
        if (dbTimestamp == null || dbTimestamp.trim().isEmpty()) {
            return "No photos yet";
        }

        try {
            Date d = dbSdf.parse(dbTimestamp);
            if (d == null) return "No photos yet";
            return uiSdf.format(d);
        } catch (ParseException e) {
            return dbTimestamp;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public ViewPreloadSizeProvider<File> getPreloadSizeProvider() {
        return preloadSizeProvider;
    }

    @NonNull
    @Override
    public List<File> getPreloadItems(int position) {
        if (position < 0 || position >= items.size()) {
            return Collections.emptyList();
        }

        String path = items.get(position).latestFilename;
        if (path == null || path.trim().isEmpty()) {
            return Collections.emptyList();
        }

        return Collections.singletonList(new File(path));
    }

    @Nullable
    @Override
    public RequestBuilder<?> getPreloadRequestBuilder(@NonNull File item) {
        return Glide.with(context)
                .load(item)
                .centerCrop()
                .override(220)
                .dontAnimate();
    }

    static class SiteVH extends RecyclerView.ViewHolder {
        com.google.android.material.card.MaterialCardView card;
        ShapeableImageView imgLatest;

        TextView tvSite;
        TextView tvMeta;
        TextView tvUnsyncedBadge;
        TextView tvStatusChip;
        TextView tvProjectLabel;
        TextView tvLocationLabel;

        ImageButton btnGeoCamera;
        View selectionOverlay;

        SiteVH(View v) {
            super(v);

            card = v.findViewById(R.id.cardSite);
            imgLatest = v.findViewById(R.id.imgLatest);

            tvSite = v.findViewById(R.id.tvSite);
            tvMeta = v.findViewById(R.id.tvMeta);
            tvUnsyncedBadge = v.findViewById(R.id.tvUnsyncedBadge);
            tvStatusChip = v.findViewById(R.id.tvStatusChip);

            tvProjectLabel = v.findViewById(R.id.tvProjectLabel);
            tvLocationLabel = v.findViewById(R.id.tvLocationLabel);

            btnGeoCamera = v.findViewById(R.id.btnGeoCamera);
            selectionOverlay = v.findViewById(R.id.viewSelectedOverlay);

            card.setCheckable(true);
        }
    }

    static class SiteItem {
        String siteId;
        int totalPhotos;
        int syncedPhotos;
        int unsyncedPhotos;
        String lastUpdated;
        String latestFilename;
        String latestTimestamp;

        String siteName;
        String project;
        String projectCode;
        String beneficiary;
        String projectId;
        String coda;
        String location;

        int pendingCount;
        int uploadingCount;
        int failedCount;
        int noProjectFoundCount;
    }


    private static String safeString(Cursor c, int idx) {
        try {
            if (c == null) return "";
            if (idx < 0 || idx >= c.getColumnCount()) return "";
            return c.isNull(idx) ? "" : c.getString(idx);
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";

        for (String v : values) {
            if (v == null) continue;
            v = v.trim();
            if (!v.isEmpty() && !v.equalsIgnoreCase("—") && !v.equals("-")) {
                return v;
            }
        }

        return "";
    }

    private static String safe(String s, String def) {
        if (s == null) return def;
        s = s.trim();
        return s.isEmpty() ? def : s;
    }

    private static int safeInt(Cursor c, int idx) {
        try {
            if (c == null) return 0;
            if (idx < 0 || idx >= c.getColumnCount()) return 0;
            return c.isNull(idx) ? 0 : c.getInt(idx);
        } catch (Exception e) {
            return 0;
        }
    }
}