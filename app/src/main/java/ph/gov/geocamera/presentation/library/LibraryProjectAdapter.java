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
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.repository.ProjectRepository;

public class LibraryProjectAdapter extends RecyclerView.Adapter<LibraryProjectAdapter.VH> implements Filterable {

    private final List<ProjectListItem> originalItems;
    private final List<ProjectListItem> filteredItems;
    private final Map<String, String> typeCache = new HashMap<>();
    private final Map<String, String> divisionCache = new HashMap<>();
    private ProjectRepository projectRepository;

    public LibraryProjectAdapter(List<ProjectListItem> items) {
        this.originalItems = items;
        this.filteredItems = new ArrayList<>(items);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refreshFromSource() {
        filteredItems.clear();
        filteredItems.addAll(originalItems);
        typeCache.clear();
        divisionCache.clear();
        notifyDataSetChanged();
    }

    public int getFilteredCount() {
        return filteredItems.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvProjectType;
        TextView tvBeneficiary;
        TextView tvProjectName;
        TextView tvLocation;
        TextView tvCost;
        View rowLocation;
        MaterialButton btnCopyProjectCode;
        MaterialButton btnProjectQr;

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

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (projectRepository == null) {
            projectRepository = new ProjectRepository(parent.getContext().getApplicationContext());
        }

        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_library_project, parent, false);
        return new VH(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ProjectListItem item = filteredItems.get(position);
        String code = clean(item.code);
        String projectId = clean(item.projectId);
        String projectType = getProjectType(projectId);
        boolean isActivity = "PROJECT_ACTIVITY".equalsIgnoreCase(projectType);
        String divisionCode = isActivity ? getDivisionCode(projectId) : "";

        if (isActivity) {
            h.tvProjectType.setText(
                    divisionCode.isEmpty()
                            ? "PROJECT ACTIVITY"
                            : "PROJECT ACTIVITY • " + divisionCode
            );
            h.tvProjectType.setTextColor(Color.parseColor("#1D4ED8"));
            h.tvProjectType.setBackgroundResource(R.drawable.bg_chip_outline_blue);
        } else {
            h.tvProjectType.setText("INFRASTRUCTURE");
            h.tvProjectType.setTextColor(Color.parseColor("#0B5A3C"));
            h.tvProjectType.setBackgroundResource(R.drawable.bg_chip_outline_green);
        }

        h.tvProjectName.setText(
                item.projectName == null || item.projectName.trim().isEmpty()
                        ? "Untitled Project"
                        : item.projectName
        );

        String beneficiary = clean(item.beneficiary);
        if (beneficiary.isEmpty()) {
            h.tvBeneficiary.setVisibility(View.GONE);
        } else {
            h.tvBeneficiary.setVisibility(View.VISIBLE);
            h.tvBeneficiary.setText(beneficiary);
        }

        String location = clean(item.location);
        boolean showCost = !isActivity && !isZeroCost(item.cost);
        boolean showLocationRow = !location.isEmpty() || showCost;

        h.rowLocation.setVisibility(showLocationRow ? View.VISIBLE : View.GONE);

        if (!location.isEmpty()) {
            h.tvLocation.setVisibility(View.VISIBLE);
            h.tvLocation.setText(location);
        } else {
            h.tvLocation.setVisibility(View.GONE);
        }

        if (showCost) {
            h.tvCost.setVisibility(View.VISIBLE);
            h.tvCost.setText(item.cost);
        } else {
            h.tvCost.setVisibility(View.GONE);
        }

        boolean hasCode = !code.isEmpty();
        h.btnCopyProjectCode.setEnabled(hasCode);
        h.btnProjectQr.setEnabled(hasCode);
        h.btnCopyProjectCode.setAlpha(hasCode ? 1f : 0.35f);
        h.btnProjectQr.setAlpha(hasCode ? 1f : 0.35f);

        h.btnCopyProjectCode.setOnClickListener(v -> {
            if (!hasCode) return;
            copyCode(v.getContext(), code);
        });

        h.btnProjectQr.setOnClickListener(v -> {
            if (!hasCode) return;
            showProjectQr(v.getContext(), code, clean(item.projectName), isActivity);
        });
    }

    private String getProjectType(String projectId) {
        if (projectId.isEmpty() || projectRepository == null) return "INFRA";
        if (typeCache.containsKey(projectId)) return typeCache.get(projectId);

        String value = clean(projectRepository.getProjectTypeById(projectId));
        if (value.isEmpty()) value = "INFRA";
        typeCache.put(projectId, value);
        return value;
    }

    private String getDivisionCode(String projectId) {
        if (projectId.isEmpty() || projectRepository == null) return "";
        if (divisionCache.containsKey(projectId)) return divisionCache.get(projectId);

        String value = clean(projectRepository.getDivisionCodeByProjectId(projectId));
        divisionCache.put(projectId, value);
        return value;
    }

    private static boolean isZeroCost(String value) {
        String v = clean(value)
                .replace("₱", "")
                .replace(",", "")
                .trim();
        if (v.isEmpty()) return true;
        try {
            return Math.abs(Double.parseDouble(v)) < 0.0001d;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void copyCode(Context context, String code) {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(context, "Clipboard is unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText("GeoKlik Project Code", code));
        Toast.makeText(context, "Project code copied", Toast.LENGTH_SHORT).show();
    }

    private static void showProjectQr(
            Context context,
            String code,
            String projectName,
            boolean isActivity
    ) {
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
            container.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 280)
            ));

            TextView tvName = new TextView(context);
            tvName.setText(projectName.isEmpty() ? "Selected Project" : projectName);
            tvName.setGravity(Gravity.CENTER);
            tvName.setTextSize(14);
            tvName.setTextColor(Color.parseColor("#111827"));
            tvName.setPadding(dp(context, 8), dp(context, 10), dp(context, 8), 0);
            container.addView(tvName);

            TextView tvType = new TextView(context);
            tvType.setText(isActivity ? "PROJECT ACTIVITY" : "INFRASTRUCTURE");
            tvType.setGravity(Gravity.CENTER);
            tvType.setTextSize(11);
            tvType.setTextColor(isActivity
                    ? Color.parseColor("#1D4ED8")
                    : Color.parseColor("#0B5A3C"));
            tvType.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), 0);
            container.addView(tvType);

            new MaterialAlertDialogBuilder(context)
                    .setTitle("Project QR")
                    .setMessage("Scan this QR from Change Project to use this project.")
                    .setView(container)
                    .setPositiveButton("Download QR", (d, w) ->
                            saveQrToDevice(context, qr, code))
                    .setNegativeButton("Close", null)
                    .show();

        } catch (Exception e) {
            Toast.makeText(context, "Unable to generate QR.", Toast.LENGTH_SHORT).show();
        }
    }

    private static void saveQrToDevice(Context context, Bitmap qr, String code) {
        if (qr == null) {
            Toast.makeText(context, "QR image is unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }

        String safeCode = clean(code).replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeCode.isEmpty()) safeCode = "PROJECT";
        String fileName = "GeoKlik_QR_" + safeCode + ".png";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/GeoKlik/QR");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            );
            if (uri == null) throw new IllegalStateException("Unable to create QR image.");

            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null || !qr.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IllegalStateException("Unable to write QR image.");
                }
                out.flush();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, ready, null, null);
            }

            Toast.makeText(
                    context,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? "QR saved to Pictures/GeoKlik/QR"
                            : "QR saved to device gallery",
                    Toast.LENGTH_LONG
            ).show();
        } catch (Exception e) {
            if (uri != null) {
                try { context.getContentResolver().delete(uri, null, null); }
                catch (Exception ignored) {}
            }
            Toast.makeText(context, "Unable to save QR.", Toast.LENGTH_LONG).show();
        }
    }

    private static Bitmap createQrBitmap(String value, int size) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(
                value,
                BarcodeFormat.QR_CODE,
                size,
                size
        );

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public int getItemCount() {
        return filteredItems.size();
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                String q = constraint == null
                        ? ""
                        : constraint.toString().trim().toLowerCase(Locale.ROOT);

                List<ProjectListItem> result = new ArrayList<>();

                if (q.isEmpty()) {
                    result.addAll(originalItems);
                } else {
                    for (ProjectListItem item : originalItems) {
                        String code = item.code == null ? "" : item.code.toLowerCase(Locale.ROOT);
                        String name = item.projectName == null ? "" : item.projectName.toLowerCase(Locale.ROOT);
                        String beneficiary = item.beneficiary == null ? "" : item.beneficiary.toLowerCase(Locale.ROOT);
                        String location = item.location == null ? "" : item.location.toLowerCase(Locale.ROOT);

                        if (code.contains(q) || name.contains(q) || beneficiary.contains(q) || location.contains(q)) {
                            result.add(item);
                        }
                    }
                }

                FilterResults fr = new FilterResults();
                fr.values = result;
                fr.count = result.size();
                return fr;
            }

            @SuppressLint("NotifyDataSetChanged")
            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                filteredItems.clear();

                if (results.values instanceof List<?>) {
                    List<?> rawList = (List<?>) results.values;
                    for (Object obj : rawList) {
                        if (obj instanceof ProjectListItem) {
                            filteredItems.add((ProjectListItem) obj);
                        }
                    }
                }

                notifyDataSetChanged();
            }
        };
    }
}
