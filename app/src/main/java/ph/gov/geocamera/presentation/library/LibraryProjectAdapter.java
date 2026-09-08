package ph.gov.geocamera.presentation.library;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.R;

public class LibraryProjectAdapter extends RecyclerView.Adapter<LibraryProjectAdapter.VH> implements Filterable {

    private final List<ProjectListItem> originalItems;
    private final List<ProjectListItem> filteredItems;

    public LibraryProjectAdapter(List<ProjectListItem> items) {
        this.originalItems = items;
        this.filteredItems = new ArrayList<>(items);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refreshFromSource() {
        filteredItems.clear();
        filteredItems.addAll(originalItems);
        notifyDataSetChanged();
    }

    public int getFilteredCount() {
        return filteredItems.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvCode;
        TextView tvBeneficiary;
        TextView tvProjectName;
        TextView tvLocation;
        TextView tvCost;
        TextView tvDateAdded;
        TextView tvDateModified;
        MaterialButton btnCopyProjectCode;
        MaterialButton btnProjectQr;

        VH(@NonNull View itemView) {
            super(itemView);
            tvCode = itemView.findViewById(R.id.tvCode);
            tvBeneficiary = itemView.findViewById(R.id.tvBeneficiary);
            tvProjectName = itemView.findViewById(R.id.tvProjectName);
            tvLocation = itemView.findViewById(R.id.tvLocation);
            tvCost = itemView.findViewById(R.id.tvCost);
            tvDateAdded = itemView.findViewById(R.id.tvDateAdded);
            tvDateModified = itemView.findViewById(R.id.tvDateModified);
            btnCopyProjectCode = itemView.findViewById(R.id.btnCopyProjectCode);
            btnProjectQr = itemView.findViewById(R.id.btnProjectQr);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_library_project, parent, false);
        return new VH(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ProjectListItem item = filteredItems.get(position);
        String code = clean(item.code);

        h.tvCode.setText(code.isEmpty() ? "NO PROJECT CODE" : code);

        h.tvBeneficiary.setText(
                item.beneficiary == null || item.beneficiary.trim().isEmpty()
                        ? "No beneficiary specified"
                        : item.beneficiary
        );

        h.tvProjectName.setText(
                item.projectName == null || item.projectName.trim().isEmpty()
                        ? "Untitled Project"
                        : item.projectName
        );

        h.tvLocation.setText(
                item.location == null || item.location.trim().isEmpty()
                        ? "Location not specified"
                        : item.location
        );

        h.tvCost.setText(
                "Cost: " + (
                        item.cost == null || item.cost.trim().isEmpty()
                                ? "₱ 0.00"
                                : item.cost
                )
        );

        h.tvDateAdded.setText(
                "Date added: " + (
                        item.dateAdded == null || item.dateAdded.trim().isEmpty()
                                ? "-"
                                : item.dateAdded
                )
        );

        h.tvDateModified.setText(
                "Modified: " + (
                        item.dateModified == null || item.dateModified.trim().isEmpty()
                                ? "-"
                                : item.dateModified
                )
        );

        boolean hasCode = !code.isEmpty();
        h.btnCopyProjectCode.setEnabled(hasCode);
        h.btnProjectQr.setEnabled(hasCode);

        h.btnCopyProjectCode.setOnClickListener(v -> {
            if (!hasCode) return;
            copyCode(v.getContext(), code);
        });

        h.btnProjectQr.setOnClickListener(v -> {
            if (!hasCode) return;
            showProjectQr(v.getContext(), code, clean(item.projectName));
        });
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

    private static void showProjectQr(Context context, String code, String projectName) {
        try {
            Bitmap qr = createQrBitmap("CODE:" + code, 720);

            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = dp(context, 20);
            container.setPadding(pad, pad, pad, dp(context, 8));

            ImageView image = new ImageView(context);
            image.setImageBitmap(qr);
            image.setAdjustViewBounds(true);
            image.setContentDescription("QR code for " + code);
            container.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 300)
            ));

            TextView tvCode = new TextView(context);
            tvCode.setText(code);
            tvCode.setGravity(Gravity.CENTER);
            tvCode.setTextSize(16);
            tvCode.setTextColor(Color.parseColor("#0B5A3C"));
            tvCode.setPadding(0, dp(context, 10), 0, 0);
            container.addView(tvCode);

            if (!projectName.isEmpty()) {
                TextView tvName = new TextView(context);
                tvName.setText(projectName);
                tvName.setGravity(Gravity.CENTER);
                tvName.setTextSize(12);
                tvName.setTextColor(Color.DKGRAY);
                tvName.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), 0);
                container.addView(tvName);
            }

            new MaterialAlertDialogBuilder(context)
                    .setTitle("Project QR")
                    .setMessage("This QR contains the project code for quick entry in GeoKlik.")
                    .setView(container)
                    .setPositiveButton("Copy Code", (d, w) -> copyCode(context, code))
                    .setNegativeButton("Close", null)
                    .show();

        } catch (Exception e) {
            Toast.makeText(context, "Unable to generate QR.", Toast.LENGTH_SHORT).show();
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
