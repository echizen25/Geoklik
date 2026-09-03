package ph.gov.geocamera.presentation.gallery;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.export.PhotoExportManager;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.presentation.map.OsmMapDialog;
import ph.gov.geocamera.presentation.map.PhotoPin;

public class PhotoActionsBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_GROUP_ID = "groupId";
    private static final String ARG_UUID = "uuid";
    private static final String ARG_TITLE = "title";
    private static final int REQ_WRITE_STORAGE = 3201;

    private String uuid = "";
    private File sourceFile;
    private MaterialButton btnSave;

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
        uuid = getArguments() != null ? getArguments().getString(ARG_UUID, "") : "";

        View btnPreview = v.findViewById(R.id.btnPreview);
        View btnMap = v.findViewById(R.id.btnMap);
        btnSave = v.findViewById(R.id.btnSave);
        View btnShare = v.findViewById(R.id.btnShare);

        ImageMetaRepository repo = new ImageMetaRepository(requireContext());
        String path = repo.getFilenameByUuid(uuid);
        sourceFile = (path == null || path.trim().isEmpty()) ? null : new File(path);

        refreshSaveState();

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

        if (btnSave != null) {
            btnSave.setOnClickListener(x -> savePhoto());
        }

        if (btnShare != null) {
            btnShare.setOnClickListener(x -> sharePhoto());
        }
    }

    private void refreshSaveState() {
        if (btnSave == null) return;

        boolean exists = sourceFile != null
                && sourceFile.exists()
                && PhotoExportManager.findExistingInGallery(requireContext(), sourceFile) != null;

        btnSave.setEnabled(!exists && sourceFile != null && sourceFile.exists());
        btnSave.setText(exists ? "Saved to Device" : "Save to Device");
        btnSave.setIconResource(exists ? android.R.drawable.checkbox_on_background : R.drawable.ic_export_24);
    }

    private void savePhoto() {
        if (sourceFile == null || !sourceFile.exists()) {
            Toast.makeText(requireContext(), "Photo file not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT <= 28 &&
                requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_STORAGE
            );
            Toast.makeText(requireContext(),
                    "Allow storage access, then tap Save again.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            PhotoExportManager.SaveResult result =
                    PhotoExportManager.saveToDevice(requireContext(), sourceFile, "Photos");

            Toast.makeText(requireContext(),
                    result.alreadySaved ? "Photo already saved to device." : "Photo saved to device.",
                    Toast.LENGTH_SHORT).show();
            refreshSaveState();
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "Save failed: " + e.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void sharePhoto() {
        if (sourceFile == null || !sourceFile.exists()) {
            Toast.makeText(requireContext(), "Photo file not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            PhotoExportManager.sharePhoto(requireContext(), sourceFile, "Share GeoKlik photo");
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "Share failed: " + e.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
