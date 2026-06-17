package ph.gov.geocamera.presentation.map;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Point;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.bumptech.glide.Glide;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.infowindow.InfoWindow;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.R;

public class OsmMapDialog extends DialogFragment {

    private static final String ARG_TITLE = "title";

    private MapView mapView;
    private MaterialSwitch switchSatellite;

    private MaterialCardView cardPreview;
    private ShapeableImageView imgPreview;
    private TextView tvPreviewTitle;
    private TextView tvPreviewMeta;

    private final List<Marker> markers = new ArrayList<>();
    private ArrayList<PhotoPin> pins = new ArrayList<>();

    private static final ITileSource ESRI_WORLD_IMAGERY =
            new OnlineTileSourceBase(
                    "EsriWorldImagery",
                    0,
                    19,
                    256,
                    ".jpg",
                    new String[]{
                            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/"
                    }) {
                @Override
                public String getTileURLString(long pMapTileIndex) {
                    int zoom = MapTileIndex.getZoom(pMapTileIndex);
                    int x = MapTileIndex.getX(pMapTileIndex);
                    int y = MapTileIndex.getY(pMapTileIndex);
                    return getBaseUrl() + "tile/" + zoom + "/" + y + "/" + x;
                }
            };

    public static OsmMapDialog newInstance(String title) {
        OsmMapDialog d = new OsmMapDialog();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        d.setArguments(b);
        return d;
    }

    public OsmMapDialog setPins(@Nullable ArrayList<PhotoPin> pins) {
        this.pins = pins != null ? pins : new ArrayList<>();
        return this;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog d = super.onCreateDialog(savedInstanceState);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_osm_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Configuration.getInstance().setUserAgentValue(requireContext().getPackageName());

        mapView = v.findViewById(R.id.mapView);
        switchSatellite = v.findViewById(R.id.switchSatellite);

        cardPreview = v.findViewById(R.id.cardPreview);
        imgPreview = v.findViewById(R.id.imgPreview);
        tvPreviewTitle = v.findViewById(R.id.tvPreviewTitle);
        tvPreviewMeta = v.findViewById(R.id.tvPreviewMeta);

        MaterialToolbar toolbar = v.findViewById(R.id.btnClose);
        if (toolbar != null) {
            Bundle args = getArguments();
            if (args != null) {
                String title = args.getString(ARG_TITLE, "Pin Map");
                toolbar.setTitle(title);
            }
            toolbar.setNavigationOnClickListener(x -> dismiss());
        }

        setupMap();
        setupSwitch();
        renderPins();
    }

    private void setupMap() {
        if (mapView == null) return;

        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(false);
        mapView.setTilesScaledToDpi(true);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMinZoomLevel(4.0);
        mapView.setMaxZoomLevel(19.0);

        mapView.post(() -> {
            if (mapView == null) return;

            if (pins != null && !pins.isEmpty()) {
                fitToPins();

                PhotoPin first = firstValidPin();
                if (first != null) {
                    onPinSelected(first);
                } else {
                    showEmptyPreview();
                }
            } else {
                BoundingBox philippines = new BoundingBox(
                        21.5,
                        126.6,
                        4.5,
                        116.8
                );
                mapView.zoomToBoundingBox(philippines, true);
                showEmptyPreview();
            }
        });
    }

    private void setupSwitch() {
        if (switchSatellite == null || mapView == null) return;

        switchSatellite.setOnCheckedChangeListener((b, checked) -> {
            GeoPoint center = (GeoPoint) mapView.getMapCenter();
            double zoom = mapView.getZoomLevelDouble();

            if (checked) {
                mapView.setTileSource(ESRI_WORLD_IMAGERY);

                if (!isInternetAvailable()) {
                    Toast.makeText(
                            requireContext(),
                            "No internet for satellite tiles",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            } else {
                mapView.setTileSource(TileSourceFactory.MAPNIK);
            }

            mapView.getController().setCenter(center);
            mapView.getController().setZoom(zoom);
            mapView.invalidate();
        });
    }

    private void renderPins() {
        if (mapView == null) return;

        InfoWindow.closeAllInfoWindowsOn(mapView);

        for (Marker old : markers) {
            mapView.getOverlays().remove(old);
        }
        markers.clear();

        if (pins == null || pins.isEmpty()) {
            showEmptyPreview();
            mapView.invalidate();
            return;
        }

        for (PhotoPin p : pins) {
            if (p == null) continue;
            if (isInvalid(p.lat, p.lng)) continue;

            Marker m = new Marker(mapView);
            m.setPosition(new GeoPoint(p.lat, p.lng));
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            m.setTitle(safeTitle(p.title));
            m.setInfoWindow(new PhotoInfoWindow(mapView, p));

            m.setOnMarkerClickListener((marker, map) -> {
                InfoWindow.closeAllInfoWindowsOn(mapView);
                zoomToPin(p);
                onPinSelected(p);

                mapView.postDelayed(marker::showInfoWindow, 180);
                return true;
            });

            markers.add(m);
            mapView.getOverlays().add(m);
        }

        mapView.invalidate();
    }

    private void zoomToPin(@NonNull PhotoPin p) {
        if (mapView == null) return;
        mapView.getController().setZoom(18.0);
        centerWithOffset(p.lat, p.lng);
    }

    private void centerWithOffset(double lat, double lng) {
        if (mapView == null) return;

        GeoPoint point = new GeoPoint(lat, lng);

        mapView.post(() -> {
            Projection projection = mapView.getProjection();
            if (projection == null) {
                mapView.getController().animateTo(point);
                return;
            }

            Point screen = projection.toPixels(point, null);
            if (screen == null) {
                mapView.getController().animateTo(point);
                return;
            }

            screen.y -= dp(130);

            GeoPoint shifted = (GeoPoint) projection.fromPixels(screen.x, screen.y);
            if (shifted != null) {
                mapView.getController().animateTo(shifted);
            } else {
                mapView.getController().animateTo(point);
            }
        });
    }

    private void fitToPins() {
        if (pins == null || pins.isEmpty() || mapView == null) return;

        PhotoPin first = firstValidPin();
        if (first == null) {
            showEmptyPreview();
            return;
        }

        int validCount = 0;
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;

        for (PhotoPin p : pins) {
            if (p == null) continue;
            if (isInvalid(p.lat, p.lng)) continue;

            validCount++;
            minLat = Math.min(minLat, p.lat);
            maxLat = Math.max(maxLat, p.lat);
            minLng = Math.min(minLng, p.lng);
            maxLng = Math.max(maxLng, p.lng);
        }

        if (validCount == 0) {
            showEmptyPreview();
            return;
        }

        if (validCount == 1) {
            zoomToPin(first);
            return;
        }

        double padLat = (maxLat - minLat) * 0.15;
        double padLng = (maxLng - minLng) * 0.15;

        if (padLat == 0) padLat = 0.002;
        if (padLng == 0) padLng = 0.002;

        BoundingBox box = new BoundingBox(
                maxLat + padLat,
                maxLng + padLng,
                minLat - padLat,
                minLng - padLng
        );

        mapView.zoomToBoundingBox(box, true);
    }

    private void onPinSelected(@Nullable PhotoPin p) {
        if (p == null) {
            showEmptyPreview();
            return;
        }

        if (tvPreviewTitle != null) {
            tvPreviewTitle.setText(safeTitle(p.title));
        }

        if (tvPreviewMeta != null) {
            tvPreviewMeta.setText(
                    "Lat: " + format(p.lat) + "   Lng: " + format(p.lng)
            );
        }

        if (imgPreview != null) {
            File f = new File(p.filename == null ? "" : p.filename);
            if (f.exists()) {
                Glide.with(requireContext())
                        .load(f)
                        .centerCrop()
                        .into(imgPreview);
            } else {
                imgPreview.setImageResource(android.R.drawable.ic_menu_gallery);
            }
        }
    }

    private void showEmptyPreview() {
        if (tvPreviewTitle != null) {
            tvPreviewTitle.setText("No Pin");
        }
        if (tvPreviewMeta != null) {
            tvPreviewMeta.setText("No GPS location for this photo.");
        }
        if (imgPreview != null) {
            imgPreview.setImageResource(android.R.drawable.ic_menu_gallery);
        }
    }

    @Nullable
    private PhotoPin firstValidPin() {
        if (pins == null) return null;

        for (PhotoPin p : pins) {
            if (p == null) continue;
            if (!isInvalid(p.lat, p.lng)) return p;
        }
        return null;
    }

    private boolean isInvalid(double lat, double lng) {
        return Double.isNaN(lat) || Double.isNaN(lng)
                || lat < -90 || lat > 90
                || lng < -180 || lng > 180
                || (Math.abs(lat) < 0.000001 && Math.abs(lng) < 0.000001);
    }

    private boolean isInternetAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) requireContext()
                        .getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);

        return caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String format(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private String safeTitle(String value) {
        if (value == null || value.trim().isEmpty()) return "Photo";
        return value.trim();
    }

    private static class PhotoInfoWindow extends InfoWindow {

        private final PhotoPin pin;

        PhotoInfoWindow(MapView mapView, PhotoPin pin) {
            super(R.layout.view_pin_tooltip, mapView);
            this.pin = pin;
        }

        @Override
        public void onOpen(Object item) {
            ShapeableImageView img = mView.findViewById(R.id.imgTooltip);
            TextView title = mView.findViewById(R.id.tvTooltipTitle);

            title.setText(pin.title == null || pin.title.trim().isEmpty() ? "Photo" : pin.title);

            File f = new File(pin.filename == null ? "" : pin.filename);
            if (f.exists()) {
                Glide.with(mView.getContext())
                        .load(f)
                        .fitCenter()
                        .into(img);
            } else {
                img.setImageResource(android.R.drawable.ic_menu_gallery);
            }
        }

        @Override
        public void onClose() {
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    public void onPause() {
        if (mapView != null) {
            InfoWindow.closeAllInfoWindowsOn(mapView);
            mapView.onPause();
        }
        super.onPause();
    }
}