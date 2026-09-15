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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;

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
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.CaptureContextRepository;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.presentation.geocamera.GeoCameraActivity;

public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.SiteVH>
        implements ListPreloader.PreloadModelProvider<File> {

    private static final String TYPE_INFRA = "INFRA";
    private static final String TYPE_PROJECT_ACTIVITY = "PROJECT_ACTIVITY";
    private static final String TYPE_PERSONAL = "PERSONAL";
    private static final String ERR_NO_PROJECT_ACTIVITY_FOUND = "NO_PROJECT_ACTIVITY_FOUND";

    public interface Callback {
        void onSyncSiteClicked(String siteId, String year, boolean alreadySynced);
        void onSelectionChanged(int selectedCount);
        void onBulkSyncRequested(List<String> siteIds);
        String getSelectedYear();
    }

    private final Context context;
    private final ImageMetaRepository imageRepo;
    private final ProjectRepository projectRepo;
    private final CameraPrefs cameraPrefs;
    private final CaptureContextRepository captureContextRepo;
    private final Callback callback;
    private final List<SiteItem> items = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private boolean selectionMode = false;

    private final SimpleDateFormat dbSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat uiSdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
    private final ViewPreloadSizeProvider<File> preloadSizeProvider = new ViewPreloadSizeProvider<>();

    public GalleryAdapter(Context ctx, ImageMetaRepository img) {
        context = ctx;
        imageRepo = img;
        projectRepo = new ProjectRepository(ctx);
        cameraPrefs = new CameraPrefs(ctx);
        captureContextRepo = new CaptureContextRepository(ctx);
        callback = (Callback) ctx;
        setHasStableIds(true);
    }

    public void loadSites(String project, String year, String type, String search) {
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
                item.projectCode = safeString(c, 13);
                item.beneficiary = safeString(c, 14);
                item.projectId = safeString(c, 15);
                item.coda = safeString(c, 16);

                String lookupId = firstNonEmpty(item.projectId, item.siteId);
                if (isPersonalSite(item.siteId)) {
                    item.projectType = TYPE_PERSONAL;
                    item.divisionCode = "";
                } else {
                    item.projectType = normalizeProjectType(projectRepo.getProjectTypeById(lookupId));
                    item.divisionCode = safe(projectRepo.getDivisionCodeByProjectId(lookupId), "");
                }

                if (!TYPE_PERSONAL.equals(item.projectType)) {
                    item.noProjectFoundCount = imageRepo.countFailedByErrorForSite(item.siteId, ImageMetaRepository.ERR_NO_PROJECT_FOUND);
                    item.noActivityFoundCount = imageRepo.countFailedByErrorForSite(item.siteId, ERR_NO_PROJECT_ACTIVITY_FOUND);
                }
                if (item.failedCount > 0) item.lastSyncError = findLatestFailedErrorForSite(item.siteId);
                if (matchesType(type, item.projectType)) items.add(item);
            }
        } finally {
            if (c != null) c.close();
        }

        if (!selected.isEmpty()) {
            Set<String> keep = new HashSet<>();
            for (SiteItem it : items) if (it.siteId != null) keep.add(it.siteId);
            selected.retainAll(keep);
            if (selected.isEmpty()) selectionMode = false;
            callback.onSelectionChanged(selected.size());
        }
        notifyDataSetChanged();
    }

    public void loadSites(String project, String year, String search) { loadSites(project, year, "ALL", search); }
    public void loadSites(String project, String year) { loadSites(project, year, "ALL", null); }

    public void clearSelection() {
        selectionMode = false;
        selected.clear();
        callback.onSelectionChanged(0);
        notifyDataSetChanged();
    }

    public List<String> getSelectedSiteIds() { return new ArrayList<>(selected); }

    public void bulkSyncSelected() {
        List<String> ids = getSelectedSiteIds();
        if (!ids.isEmpty()) callback.onBulkSyncRequested(ids);
    }

    private void toggleSelection(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) return;
        if (selected.contains(siteId)) selected.remove(siteId); else selected.add(siteId);
        if (selected.isEmpty()) selectionMode = false;
        callback.onSelectionChanged(selected.size());
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) {
        String id = items.get(position).siteId;
        return id == null ? position : id.hashCode();
    }

    @NonNull @Override
    public SiteVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new SiteVH(LayoutInflater.from(context).inflate(R.layout.item_site, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull SiteVH h, int position) {
        SiteItem item = items.get(position);
        preloadSizeProvider.setView(h.imgLatest);
        boolean projectActivity = TYPE_PROJECT_ACTIVITY.equals(item.projectType);
        boolean personal = TYPE_PERSONAL.equals(item.projectType);

        String title = personal ? "Personal Capture"
                : projectActivity
                ? firstNonEmpty(item.coda, item.project, item.siteName, item.projectCode, item.siteId)
                : firstNonEmpty(item.projectCode, item.project, item.siteName, item.siteId);
        h.tvSite.setText(safe(title, personal ? "Personal Capture" : projectActivity ? "Project Activity" : "SITE"));

        h.tvProjectLabel.setVisibility(View.VISIBLE);
        if (personal) {
            h.tvProjectLabel.setText("PERSONAL CAPTURE");
        } else if (projectActivity) {
            String line = "PROJECT ACTIVITY";
            if (!item.divisionCode.isEmpty()) line += " • " + item.divisionCode;
            h.tvProjectLabel.setText(line);
        } else {
            String beneficiary = firstNonEmpty(item.beneficiary, item.coda);
            h.tvProjectLabel.setText(beneficiary.isEmpty() ? "INFRASTRUCTURE" : "INFRASTRUCTURE • " + beneficiary);
        }

        String locationLine = firstNonEmpty(item.location);
        if (locationLine.isEmpty()) {
            h.tvLocationLabel.setVisibility(View.GONE);
            h.tvLocationLabel.setText("");
        } else {
            h.tvLocationLabel.setVisibility(View.VISIBLE);
            h.tvLocationLabel.setText("Location: " + locationLine);
        }

        h.tvMeta.setText(item.totalPhotos + " photos • " + formatMonthDayYearFromDb(item.latestTimestamp));
        bindUnsyncedBadge(h, item);
        bindStatusChip(h, item);
        bindThumbnail(h, item);
        h.btnGeoCamera.setOnClickListener(v -> openCameraForItem(item));

        h.card.setOnClickListener(v -> {
            if (selectionMode) { toggleSelection(item.siteId); return; }
            Intent i = new Intent(context, SiteDatesActivity.class);
            i.putExtra(SiteDatesActivity.EXTRA_SITE_ID, item.siteId);
            i.putExtra(SiteDatesActivity.EXTRA_YEAR, callback.getSelectedYear());
            i.putExtra(SiteDatesActivity.EXTRA_SITE_NAME, safe(title, "Photos"));
            i.putExtra(SiteDatesActivity.EXTRA_CAPTURE_TYPE, item.projectType);
            i.putExtra(SiteDatesActivity.EXTRA_DIVISION_CODE, item.divisionCode);
            context.startActivity(i);
        });
        h.card.setOnLongClickListener(v -> { selectionMode = true; toggleSelection(item.siteId); return true; });
        h.imgLatest.setOnClickListener(v -> { if (selectionMode) toggleSelection(item.siteId); else showProjectDetails(item); });

        boolean checked = selected.contains(item.siteId);
        h.card.setChecked(checked);
        h.selectionOverlay.setVisibility(checked ? View.VISIBLE : View.GONE);
    }

    private void openCameraForItem(@NonNull SiteItem item) {
        String type = normalizeProjectType(item.projectType);
        if (TYPE_PERSONAL.equals(type)) {
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PERSONAL);
            cameraPrefs.clearActivityProjectId();
            cameraPrefs.saveSite(null, true);
            captureContextRepo.setCurrent(CameraPrefs.DOC_PERSONAL, null);
        } else if (TYPE_PROJECT_ACTIVITY.equals(type)) {
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);
            cameraPrefs.saveActivityProjectId(item.siteId);
            cameraPrefs.saveSite(item.siteId, false);
            captureContextRepo.setCurrent(CameraPrefs.DOC_PROJECT_ACTIVITY, item.siteId);
        } else {
            cameraPrefs.saveDocumentationType(CameraPrefs.DOC_INFRA);
            cameraPrefs.clearActivityProjectId();
            cameraPrefs.saveSite(item.siteId, false);
            captureContextRepo.setCurrent(CameraPrefs.DOC_INFRA, null);
        }
        Intent i = new Intent(context, GeoCameraActivity.class);
        if (!TYPE_PERSONAL.equals(type)) i.putExtra("siteId", item.siteId);
        context.startActivity(i);
    }

    private boolean hasUnclassifiedUnsynced(@NonNull SiteItem item) {
        return item.totalPhotos > 0 && item.unsyncedPhotos > 0
                && item.pendingCount == 0 && item.uploadingCount == 0 && item.failedCount == 0
                && item.noProjectFoundCount == 0 && item.noActivityFoundCount == 0;
    }

    private void bindUnsyncedBadge(@NonNull SiteVH h, @NonNull SiteItem item) {
        if (TYPE_PERSONAL.equals(item.projectType) || item.uploadingCount > 0) {
            h.tvUnsyncedBadge.setVisibility(View.GONE);
        } else if (item.unsyncedPhotos > 0) {
            h.tvUnsyncedBadge.setVisibility(View.VISIBLE);
            h.tvUnsyncedBadge.setText(item.unsyncedPhotos > 9 ? "9+" : String.valueOf(item.unsyncedPhotos));
        } else h.tvUnsyncedBadge.setVisibility(View.GONE);
    }

    private void bindStatusChip(@NonNull SiteVH h, @NonNull SiteItem item) {
        h.tvStatusChip.setVisibility(View.VISIBLE);
        if (TYPE_PERSONAL.equals(item.projectType)) { setStatus(h, "ON DEVICE", "#546E7A", 1f); return; }
        if (item.totalPhotos <= 0) { setStatus(h, "EMPTY", null, 0.75f); return; }
        if (item.uploadingCount > 0) { setStatus(h, "UPLOADING", "#F57C00", 1f); return; }
        if (item.noProjectFoundCount > 0) { setStatus(h, "NO PROJECT", "#C62828", 1f); return; }
        if (item.noActivityFoundCount > 0) { setStatus(h, "NO ACTIVITY", "#C62828", 1f); return; }
        if (item.failedCount > 0) { setStatus(h, "FAILED", "#C62828", 1f); return; }
        if (item.pendingCount > 0 || hasUnclassifiedUnsynced(item)) { setStatus(h, "PENDING", "#004B24", 1f); return; }
        setStatus(h, "SYNCED", "#2E7D32", 0.9f);
    }

    private void setStatus(@NonNull SiteVH h, String text, String color, float alpha) {
        h.tvStatusChip.setText(text);
        h.tvStatusChip.setAlpha(alpha);
        h.tvStatusChip.setTextColor(color == null ? Color.GRAY : Color.parseColor(color));
    }

    private void bindThumbnail(@NonNull SiteVH h, @NonNull SiteItem item) {
        if (item.latestFilename != null && !item.latestFilename.trim().isEmpty()) {
            Glide.with(context).load(new File(item.latestFilename)).thumbnail(0.25f).centerCrop()
                    .placeholder(R.drawable.ph_shimer_tiny).error(android.R.drawable.ic_menu_gallery).into(h.imgLatest);
        } else {
            Glide.with(context).clear(h.imgLatest);
            h.imgLatest.setImageResource(android.R.drawable.ic_menu_gallery);
        }
    }

    private String displayStatus(@NonNull SiteItem item) {
        if (TYPE_PERSONAL.equals(item.projectType)) return "ON DEVICE";
        if (item.uploadingCount > 0) return "UPLOADING";
        if (item.noProjectFoundCount > 0) return "NO PROJECT";
        if (item.noActivityFoundCount > 0) return "NO ACTIVITY";
        if (item.failedCount > 0) return "FAILED";
        if (item.pendingCount > 0 || hasUnclassifiedUnsynced(item)) return "PENDING";
        return "SYNCED";
    }

    private void showProjectDetails(@NonNull SiteItem item) {
        boolean projectActivity = TYPE_PROJECT_ACTIVITY.equals(item.projectType);
        boolean personal = TYPE_PERSONAL.equals(item.projectType);
        String title = personal ? "Personal Capture"
                : projectActivity ? firstNonEmpty(item.coda, item.project, item.siteName, item.projectCode, item.siteId)
                : firstNonEmpty(item.projectCode, item.project, item.siteName, item.siteId);

        StringBuilder message = new StringBuilder();
        if (personal) {
            message.append("Type: Personal Capture\nStorage: On device only\n");
        } else {
            message.append("Type: ").append(projectActivity ? "Project Activity" : "Infrastructure").append("\n");
            if (projectActivity) {
                message.append("Project Title: ").append(safe(item.coda, safe(title, "—"))).append("\n");
                if (!item.divisionCode.isEmpty()) message.append("Division: ").append(item.divisionCode).append("\n");
            } else message.append("Beneficiary: ").append(safe(item.beneficiary, "—")).append("\n");
            message.append("Code: ").append(safe(item.projectCode, "—")).append("\n")
                    .append("Project ID: ").append(safe(item.projectId, "—")).append("\n");
        }
        message.append("Location: ").append(safe(item.location, "—")).append("\n\nTotal Photos: ").append(item.totalPhotos).append("\n");
        if (!personal) message.append("Synced: ").append(item.syncedPhotos).append("\nPending: ").append(item.pendingCount)
                .append("\nUploading: ").append(item.uploadingCount).append("\nFailed: ").append(item.failedCount).append("\n");
        message.append("Status: ").append(displayStatus(item));
        if (!item.lastSyncError.isEmpty() && !personal) message.append("\nLast Error: ").append(friendlyError(item.lastSyncError));
        message.append("\n\nLatest: ").append(formatMonthDayYearFromDb(item.latestTimestamp));

        new MaterialAlertDialogBuilder(context)
                .setTitle(personal ? "Personal Capture" : projectActivity ? "Project Activity Details" : "Project Details")
                .setMessage(message.toString())
                .setPositiveButton("Open Photos", (d, w) -> {
                    Intent i = new Intent(context, SiteDatesActivity.class);
                    i.putExtra(SiteDatesActivity.EXTRA_SITE_ID, item.siteId);
                    i.putExtra(SiteDatesActivity.EXTRA_YEAR, callback.getSelectedYear());
                    i.putExtra(SiteDatesActivity.EXTRA_SITE_NAME, safe(title, "Photos"));
                    i.putExtra(SiteDatesActivity.EXTRA_CAPTURE_TYPE, item.projectType);
                    i.putExtra(SiteDatesActivity.EXTRA_DIVISION_CODE, item.divisionCode);
                    context.startActivity(i);
                }).setNegativeButton("Close", null).show();
    }

    private String findLatestFailedErrorForSite(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) return "";
        Cursor c = null;
        try {
            c = imageRepo.getFailedSyncItems(100);
            while (c != null && c.moveToNext()) {
                if (siteId.equalsIgnoreCase(safeString(c, 2))) return safeString(c, 4);
            }
        } finally { if (c != null) c.close(); }
        return "";
    }

    private static String friendlyError(String raw) {
        String error = raw == null ? "" : raw.trim();
        if (error.isEmpty()) return "Unknown sync error";
        if (ImageMetaRepository.ERR_NO_PROJECT_FOUND.equalsIgnoreCase(error)) return "Project was not found on the server.";
        if (ERR_NO_PROJECT_ACTIVITY_FOUND.equalsIgnoreCase(error)) return "Project Activity was not found on the server.";
        if (error.startsWith("HTTP_403")) return "Server denied the upload (HTTP 403).";
        if (error.startsWith("HTTP_404")) return "Upload endpoint was not found (HTTP 404).";
        if (error.startsWith("HTTP_5")) return "Server error. Please retry when the service is available.";
        if (error.startsWith("IO_")) return "Network connection was interrupted.";
        if (error.startsWith("FILE_MISSING")) return "Local photo file is missing.";
        return error.length() > 140 ? error.substring(0, 140) + "…" : error;
    }

    private String formatMonthDayYearFromDb(String dbTimestamp) {
        if (dbTimestamp == null || dbTimestamp.trim().isEmpty()) return "No photos yet";
        try {
            Date d = dbSdf.parse(dbTimestamp);
            return d == null ? "No photos yet" : uiSdf.format(d);
        } catch (ParseException e) { return dbTimestamp; }
    }

    @Override public int getItemCount() { return items.size(); }
    public ViewPreloadSizeProvider<File> getPreloadSizeProvider() { return preloadSizeProvider; }

    @NonNull @Override
    public List<File> getPreloadItems(int position) {
        if (position < 0 || position >= items.size()) return Collections.emptyList();
        String path = items.get(position).latestFilename;
        return path == null || path.trim().isEmpty() ? Collections.emptyList() : Collections.singletonList(new File(path));
    }

    @Nullable @Override
    public RequestBuilder<?> getPreloadRequestBuilder(@NonNull File item) {
        return Glide.with(context).load(item).centerCrop().override(220).dontAnimate();
    }

    static class SiteVH extends RecyclerView.ViewHolder {
        com.google.android.material.card.MaterialCardView card;
        ShapeableImageView imgLatest;
        TextView tvSite, tvMeta, tvUnsyncedBadge, tvStatusChip, tvProjectLabel, tvLocationLabel;
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
        String siteId, lastUpdated, latestFilename, latestTimestamp, siteName, project, projectCode;
        String beneficiary, projectId, coda, location, projectType, divisionCode, lastSyncError = "";
        int totalPhotos, syncedPhotos, unsyncedPhotos, pendingCount, uploadingCount, failedCount;
        int noProjectFoundCount, noActivityFoundCount;
    }

    private static boolean matchesType(String requestedType, String itemType) {
        String requested = requestedType == null ? "ALL" : requestedType.trim().toUpperCase(Locale.US);
        return requested.isEmpty() || "ALL".equals(requested) || requested.equals(normalizeProjectType(itemType));
    }

    private static String normalizeProjectType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.US);
        if (TYPE_PROJECT_ACTIVITY.equals(type)) return TYPE_PROJECT_ACTIVITY;
        if (TYPE_PERSONAL.equals(type)) return TYPE_PERSONAL;
        return TYPE_INFRA;
    }

    private static boolean isPersonalSite(String siteId) { return siteId != null && "UNCAT".equalsIgnoreCase(siteId.trim()); }
    private static String safeString(Cursor c, int idx) {
        try { return c == null || idx < 0 || idx >= c.getColumnCount() || c.isNull(idx) ? "" : c.getString(idx); }
        catch (Exception e) { return ""; }
    }
    private static int safeInt(Cursor c, int idx) {
        try { return c == null || idx < 0 || idx >= c.getColumnCount() || c.isNull(idx) ? 0 : c.getInt(idx); }
        catch (Exception e) { return 0; }
    }
    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v == null) continue;
            v = v.trim();
            if (!v.isEmpty() && !v.equalsIgnoreCase("—") && !v.equals("-")) return v;
        }
        return "";
    }
    private static String safe(String s, String def) {
        if (s == null) return def;
        s = s.trim();
        return s.isEmpty() ? def : s;
    }
}