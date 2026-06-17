package ph.gov.geocamera.presentation.library;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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

        VH(@NonNull View itemView) {
            super(itemView);
            tvCode = itemView.findViewById(R.id.tvCode);
            tvBeneficiary = itemView.findViewById(R.id.tvBeneficiary);
            tvProjectName = itemView.findViewById(R.id.tvProjectName);
            tvLocation = itemView.findViewById(R.id.tvLocation);
            tvCost = itemView.findViewById(R.id.tvCost);
            tvDateAdded = itemView.findViewById(R.id.tvDateAdded);
            tvDateModified = itemView.findViewById(R.id.tvDateModified);
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

        h.tvCode.setText(
                item.code == null || item.code.trim().isEmpty()
                        ? "-"
                        : item.code
        );

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