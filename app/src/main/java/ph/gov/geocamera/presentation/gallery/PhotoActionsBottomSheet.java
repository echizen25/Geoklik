package ph.gov.geocamera.presentation.gallery;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.imageview.ShapeableImageView;

import java.io.File;
import java.util.ArrayList;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.presentation.map.OsmMapDialog;
import ph.gov.geocamera.presentation.map.PhotoPin;

public class PhotoActionsBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_GROUP_ID = "groupId";
    private static final String ARG_UUID = "uuid";
    private static final String ARG_TITLE = "title";

    public static PhotoActionsBottomSheet newInstance(String groupId, String uuid, String title) {
        PhotoActionsBottomSheet b = new PhotoActionsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_GROUP_ID, groupId);
        args.putString(ARG_UUID, uuid);
        args.putString(ARG_TITLE, title);
        b.setArguments(args);
        return b;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bs_photo_actions, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        final String groupId = getArguments() != null ? getArguments().getString(ARG_GROUP_ID, "") : "";
        final String uuid = getArguments() != null ? getArguments().getString(ARG_UUID, "") : "";
        final String title = getArguments() != null ? getArguments().getString(ARG_TITLE, "Photo") : "Photo";

        ShapeableImageView img = v.findViewById(R.id.imgThumb);
        TextView tvTitle = v.findViewById(R.id.tvTitle);
        TextView tvMeta = v.findViewById(R.id.tvMeta);

        View btnClose = v.findViewById(R.id.btnClose);
        View btnPreview = v.findViewById(R.id.btnPreview);
        View btnMap = v.findViewById(R.id.btnMap);

        if (tvTitle != null) tvTitle.setText(title);

        ImageMetaRepository repo = new ImageMetaRepository(requireContext());

        // ✅ Load pin once for display (thumb + meta)
        PhotoPin pin = repo.getPhotoPinByUuid(uuid);

        if (tvMeta != null) tvMeta.setText("No location");
        if (img != null) img.setImageResource(android.R.drawable.ic_menu_gallery);

        if (pin != null) {
            if (tvMeta != null) tvMeta.setText("Lat: " + pin.lat + " • Lng: " + pin.lng);

            String path = pin.filename == null ? "" : pin.filename.trim();
            if (img != null && !path.isEmpty() && new File(path).exists()) {
                Glide.with(requireContext())
                        .load(new File(path))
                        .centerCrop()
                        .into(img);
            }
        }

        if (btnClose != null) btnClose.setOnClickListener(x -> dismissAllowingStateLoss());

        if (btnPreview != null) {
            btnPreview.setOnClickListener(x -> {
                dismissAllowingStateLoss();
                if (getActivity() instanceof GroupImagesActivity) {
                    ((GroupImagesActivity) getActivity()).openPreview(groupId, uuid);
                }
            });
        }

        if (btnMap != null) {
            btnMap.setOnClickListener(x -> {

                // ✅ fetch fresh (avoid stale captured pin)
                PhotoPin p = repo.getPhotoPinByUuid(uuid);

                if (p == null) {
                    Toast.makeText(requireContext(), "No pin data", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (Double.isNaN(p.lat) || Double.isNaN(p.lng) ||
                        (Math.abs(p.lat) < 0.000001 && Math.abs(p.lng) < 0.000001)) {
                    Toast.makeText(requireContext(), "No GPS saved for this photo", Toast.LENGTH_SHORT).show();
                    return;
                }

                ArrayList<PhotoPin> one = new ArrayList<>();
                one.add(p);

                try {
                    // ✅ NEW CALL STYLE (non-parcel pins)
                    OsmMapDialog d = OsmMapDialog.newInstance("Pin on Map")
                            .setPins(one);

                    d.show(getParentFragmentManager(), "osm_map");
                    dismissAllowingStateLoss();
                } catch (Exception e) {
                    Toast.makeText(requireContext(),
                            "Failed to open map: " + e.getClass().getSimpleName(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}