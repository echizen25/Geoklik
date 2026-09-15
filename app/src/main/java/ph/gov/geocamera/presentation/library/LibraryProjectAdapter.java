package ph.gov.geocamera.presentation.library;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.R;

public class LibraryProjectAdapter extends RecyclerView.Adapter<LibraryProjectAdapter.VH> implements Filterable {

    private static final String FILTER_ALL = "ALL";
    private static final String FILTER_INFRA = "INFRA";
    private static final String FILTER_PROJECT = "PROJECT";
    private static final String FILTER_ACTIVITY = "ACTIVITY";

    private final List<ProjectListItem> originalItems;
    private final List<ProjectListItem> filteredItems;
    private String searchQuery = "";
    private String typeFilter = FILTER_ALL;

    public LibraryProjectAdapter(List<ProjectListItem> items) {
        this.originalItems = items;
        this.filteredItems = new ArrayList<>(items);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refreshFromSource() { applyFilters(); }

    public void setTypeFilter(String type) {
        String value = clean(type).toUpperCase(Locale.US);
        typeFilter = FILTER_INFRA.equals(value) || FILTER_PROJECT.equals(value) || FILTER_ACTIVITY.equals(value)
                ? value : FILTER_ALL;
        applyFilters();
    }

    public int getFilteredCount() { return filteredItems.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvProjectType, tvBeneficiary, tvProjectName, tvLocation, tvCost;
        View rowLocation;
        MaterialButton btnCopyProjectCode, btnProjectQr;
        VH(@NonNull View itemView) {
            super(itemView);
            tvProjectType = itemView.findViewById(R.id.tvProjectType);
            tvBeneficiary = itemView.findViewById(R.id.tvBeneficiary);
            tvProjectName = itemView.findViewById(R.id.tvProjectName);
            tvLocation = itemView.findViewById(R.id.tvLocation);
            tvCost = itemView.findViewById(R.id.tvCost);
            rowLocation = itemView.findViewById(R.id.rowLocation);
            btnCopyProjectCode = itemView.findViewById(R.id.btnCopyProjectCode);
            btnProjectQr = itemView.findViewById(R.id.btnProjectQr);
        }
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_library_project, parent, false));
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ProjectListItem item = filteredItems.get(position);
        String code = clean(item.code);
        String projectType = normalizeType(item.projectType);
        boolean standaloneActivity = "ACTIVITY".equals(projectType);
        boolean projectActivity = "PROJECT_ACTIVITY".equals(projectType);
        boolean standaloneProject = "PROJECT".equals(projectType);
        boolean anyActivity = standaloneActivity || projectActivity;
        String divisionCode = clean(item.divisionCode);

        if (standaloneActivity) {
            h.tvProjectType.setText(divisionCode.isEmpty() ? "ACTIVITY" : "ACTIVITY • " + divisionCode);
            h.tvProjectType.setTextColor(Color.parseColor("#0B5A3C"));
            h.tvProjectType.setBackgroundResource(R.drawable.bg_chip_outline_green);
        } else if (projectActivity) {
            h.tvProjectType.setText(divisionCode.isEmpty() ? "PROJECT ACTIVITY" : "PROJECT ACTIVITY • " + divisionCode);
            h.tvProjectType.setTextColor(Color.parseColor("#1D4ED8"));
            h.tvProjectType.setBackgroundResource(R.drawable.bg_chip_outline_blue);
        } else if (standaloneProject) {
            h.tvProjectType.setText(divisionCode.isEmpty() ? "PROJECT" : "PROJECT • " + divisionCode);
            h.tvProjectType.setTextColor(Color.parseColor("#1D4ED8"));
            h.tvProjectType.setBackgroundResource(R.drawable.bg_chip_outline_blue);
        } else {
            h.tvProjectType.setText("INFRASTRUCTURE");
            h.tvProjectType.setTextColor(Color.parseColor("#0B5A3C"));
            h.tvProjectType.setBackgroundResource(R.drawable.bg_chip_outline_green);
        }

        h.tvProjectName.setText(clean(item.projectName).isEmpty() ? "Untitled Project" : item.projectName);

        boolean divisionOwned = anyActivity || standaloneProject;
        String secondary = divisionOwned ? divisionDisplay(item) : clean(item.beneficiary);
        if (secondary.isEmpty()) {
            h.tvBeneficiary.setVisibility(View.GONE);
        } else {
            h.tvBeneficiary.setVisibility(View.VISIBLE);
            h.tvBeneficiary.setText(divisionOwned ? "Division: " + secondary : secondary);
        }

        String location = clean(item.location);
        boolean showCost = !anyActivity && !isZeroCost(item.cost);
        h.rowLocation.setVisibility(!location.isEmpty() || showCost ? View.VISIBLE : View.GONE);
        h.tvLocation.setVisibility(location.isEmpty() ? View.GONE : View.VISIBLE);
        if (!location.isEmpty()) h.tvLocation.setText(location);
        h.tvCost.setVisibility(showCost ? View.VISIBLE : View.GONE);
        if (showCost) h.tvCost.setText(item.cost);

        boolean hasCode = !code.isEmpty();
        h.btnCopyProjectCode.setEnabled(hasCode);
        h.btnProjectQr.setEnabled(hasCode);
        h.btnCopyProjectCode.setAlpha(hasCode ? 1f : 0.35f);
        h.btnProjectQr.setAlpha(hasCode ? 1f : 0.35f);
        h.btnCopyProjectCode.setOnClickListener(v -> { if (hasCode) copyCode(v.getContext(), code); });
        h.btnProjectQr.setOnClickListener(v -> { if (hasCode) showProjectQr(v.getContext(), code, clean(item.projectName), projectType); });
    }

    private static String divisionDisplay(ProjectListItem item) {
        String code = clean(item.divisionCode);
        String name = clean(item.divisionName);
        if (!code.isEmpty()) return code;
        return name;
    }

    private boolean matchesType(ProjectListItem item) {
        if (FILTER_ALL.equals(typeFilter)) return true;
        String type = normalizeType(item.projectType);
        if (FILTER_INFRA.equals(typeFilter)) return "INFRA".equals(type);
        if (FILTER_PROJECT.equals(typeFilter)) return "PROJECT".equals(type);
        if (FILTER_ACTIVITY.equals(typeFilter)) return "ACTIVITY".equals(type) || "PROJECT_ACTIVITY".equals(type);
        return true;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void applyFilters() {
        filteredItems.clear();
        String q = searchQuery.toLowerCase(Locale.ROOT);
        for (ProjectListItem item : originalItems) {
            if (item == null || !matchesType(item)) continue;
            String haystack = (clean(item.code) + " " + clean(item.projectName) + " " + clean(item.beneficiary)
                    + " " + clean(item.location) + " " + clean(item.divisionCode) + " " + clean(item.divisionName)
                    + " " + normalizeType(item.projectType)).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || haystack.contains(q)) filteredItems.add(item);
        }
        notifyDataSetChanged();
    }

    private static String normalizeType(String value) {
        String type = clean(value).toUpperCase(Locale.US);
        if ("ACTIVITY".equals(type)) return "ACTIVITY";
        if ("PROJECT_ACTIVITY".equals(type)) return "PROJECT_ACTIVITY";
        if ("PROJECT".equals(type)) return "PROJECT";
        return "INFRA";
    }

    private static boolean isZeroCost(String value) {
        String v = clean(value).replace("₱", "").replace(",", "").trim();
        if (v.isEmpty()) return true;
        try { return Math.abs(Double.parseDouble(v)) < 0.0001d; }
        catch (Exception ignored) { return false; }
    }

    private static void copyCode(Context context, String code) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) { Toast.makeText(context, "Clipboard is unavailable.", Toast.LENGTH_SHORT).show(); return; }
        clipboard.setPrimaryClip(ClipData.newPlainText("GeoKlik Project Code", code));
        Toast.makeText(context, "Project code copied", Toast.LENGTH_SHORT).show();
    }

    private static void showProjectQr(Context context, String code, String projectName, String projectType) {
        try {
            Bitmap qr = createQrBitmap("CODE:" + code, 720);
            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = dp(context, 18);
            container.setPadding(pad, pad, pad, dp(context, 6));

            ImageView image = new ImageView(context);
            image.setImageBitmap(qr);
            image.setAdjustViewBounds(true);
            image.setContentDescription("QR code for selected project");
            container.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 280)));

            TextView tvName = new TextView(context);
            tvName.setText(projectName.isEmpty() ? "Selected Project" : projectName);
            tvName.setGravity(Gravity.CENTER);
            tvName.setTextSize(14);
            tvName.setTextColor(Color.parseColor("#111827"));
            tvName.setPadding(dp(context, 8), dp(context, 10), dp(context, 8), 0);
            container.addView(tvName);

            TextView tvType = new TextView(context);
            String label = "ACTIVITY".equals(projectType) ? "ACTIVITY"
                    : "PROJECT_ACTIVITY".equals(projectType) ? "PROJECT ACTIVITY"
                    : "PROJECT".equals(projectType) ? "PROJECT" : "INFRASTRUCTURE";
            tvType.setText(label);
            tvType.setGravity(Gravity.CENTER);
            tvType.setTextSize(11);
            tvType.setTextColor(("PROJECT_ACTIVITY".equals(projectType) || "PROJECT".equals(projectType))
                    ? Color.parseColor("#1D4ED8") : Color.parseColor("#0B5A3C"));
            tvType.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), 0);
            container.addView(tvType);

            new MaterialAlertDialogBuilder(context)
                    .setTitle("Project QR")
                    .setMessage("Scan this QR from Change Project to use this documentation target.")
                    .setView(container)
                    .setPositiveButton("Download QR", (d, w) -> saveQrToDevice(context, qr, code))
                    .setNegativeButton("Close", null).show();
        } catch (Exception e) { Toast.makeText(context, "Unable to generate QR.", Toast.LENGTH_SHORT).show(); }
    }

    private static void saveQrToDevice(Context context, Bitmap qr, String code) {
        if (qr == null) { Toast.makeText(context, "QR image is unavailable.", Toast.LENGTH_SHORT).show(); return; }
        String safeCode = clean(code).replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeCode.isEmpty()) safeCode = "PROJECT";
        String fileName = "GeoKlik_QR_" + safeCode + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GeoKlik/QR");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Unable to create QR image.");
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null || !qr.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Unable to write QR image.");
                out.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues(); ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, ready, null, null);
            }
            Toast.makeText(context, Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? "QR saved to Pictures/GeoKlik/QR" : "QR saved to device gallery", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            if (uri != null) try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
            Toast.makeText(context, "Unable to save QR.", Toast.LENGTH_LONG).show();
        }
    }

    private static Bitmap createQrBitmap(String value, int size) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
        return bitmap;
    }

    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    @Override public int getItemCount() { return filteredItems.size(); }

    @Override public Filter getFilter() {
        return new Filter() {
            @Override protected FilterResults performFiltering(CharSequence constraint) {
                searchQuery = constraint == null ? "" : constraint.toString().trim();
                FilterResults fr = new FilterResults(); fr.values = searchQuery; return fr;
            }
            @Override protected void publishResults(CharSequence constraint, FilterResults results) { applyFilters(); }
        };
    }
}
