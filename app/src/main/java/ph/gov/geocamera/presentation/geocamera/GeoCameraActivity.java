package ph.gov.geocamera.presentation.geocamera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import android.location.Address;
import android.location.Geocoder;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.Surface;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.gms.location.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.local.db.GeoDbHelper;
import ph.gov.geocamera.data.repository.GroupRepository;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.data.repository.ProjectRepository;
import ph.gov.geocamera.data.repository.SiteRepository;
import ph.gov.geocamera.data.repository.UserRepository;
import ph.gov.geocamera.data.sync.SyncScheduler;
import ph.gov.geocamera.presentation.site.SetSiteActivity;

public class GeoCameraActivity extends AppCompatActivity {

    private static final String QR_HMAC_SECRET = "CHANGE_ME_TO_A_LONG_RANDOM_SECRET";
    private static final int QR_VERSION = 1;

    private PreviewView previewView;
    private ImageButton btnCapture, btnCamSettings;

    private android.widget.TextView tvLatLng, tvAccuracy, tvProject, tvDesc, tvWatermarkLatLng;
    private android.widget.TextView tvDate, tvAddress;
    private View viewAccuracyIndicator;
    private android.widget.TextView tvAccuracyBadge;

    private View viewShutterFlash;

    private ImageCapture imageCapture;
    private Executor mainExecutor;

    // =========================
    // LOCATION (GPS strict + indoor assist)
    // =========================
    private LocationManager locationManager;

    private FusedLocationProviderClient fusedLocationClient;
    private SettingsClient settingsClient;
    private LocationCallback fusedCallback;
    private LocationRequest fusedRequest;

    private volatile Location gpsLastLocation = null;
    private volatile Location gpsBestLocation = null;

    private volatile Location fusedLastLocation = null;

    // Used for UI + watermark text display (current best chosen)
    private Location lastLocation;

    private volatile int gnssUsedInFix = 0;
    private volatile int gnssTotal = 0;
    private volatile long lastGnssAt = 0L;

    // ✅ time-based stabilization
    private long firstGoodFixAt = 0L;
    private static final long REQUIRED_GOOD_MS = 1800; // adjust: 1200–2500ms

    private static final long GPS_FRESH_MS = 8_000; // strict freshness

    private CameraPrefs cameraPrefs;
    private String activeSiteId = null;
    private String activeProjectId = null;
    private String activeProjectLabel = null;
    private boolean activeUncategorized = true;

    private String activeDescription = null;
    private android.view.OrientationEventListener orientationListener;
    private UserRepository userRepo;
    private GroupRepository groupRepo;
    private SiteRepository siteRepo;
    private ImageMetaRepository imageRepo;
    private GeoDbHelper dbHelper;
    private boolean isPickingSite = false;
    // Thresholds
    private static final float OUTDOOR_GOOD_ACC = 8f;     // very good GPS
    private static final float OUTDOOR_OK_ACC   = 18f;    // acceptable GPS for compliance
    private static final float INDOOR_MAX_ACC   = 60f;    // indoor assist cap (estimate)

    // legacy (kept, but not used in GPS-only anymore)
    private static final int REQUIRED_STABLE_FIX_COUNT = 2;
    private int stableFixCount = 0;
    private Preview previewUseCase;
    private volatile boolean isCapturing = false;

    private int lastRotation = Surface.ROTATION_0;

    // ✅ Mode A default: GPS-only strict
    // ✅ Manual toggle: Indoor Assist (estimate) with confirmation dialog
    private boolean allowIndoorFallback = false;

    // Satellites requirement (typical minimum)
    private static final int MIN_USED_SATS = 4;

    private ActivityResultLauncher<Intent> setSiteLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ProjectRepository projectRepo;
    private long lastGeocodeAt = 0L;
    private double lastGeoLat = 0.0;
    private double lastGeoLng = 0.0;
    private String lastAddressText = "Province";
    private android.widget.TextView tvCaptureStatus;

    private Bitmap decodeScaled(String path, int maxW, int maxH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);

        int inSampleSize = 1;
        while ((o.outWidth / inSampleSize) > maxW || (o.outHeight / inSampleSize) > maxH) {
            inSampleSize *= 2;
        }

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = inSampleSize;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, o2);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_geocamera);

        mainExecutor = ContextCompat.getMainExecutor(this);
        dbHelper = new GeoDbHelper(this);
        tvCaptureStatus = findViewById(R.id.tvCaptureStatus);

        userRepo = new UserRepository(this);
        groupRepo = new GroupRepository(this);
        siteRepo = new SiteRepository(this);
        imageRepo = new ImageMetaRepository(this);

        cameraPrefs = new CameraPrefs(this);
        allowIndoorFallback = cameraPrefs.isIndoorAssistEnabled();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        settingsClient = LocationServices.getSettingsClient(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        previewView = findViewById(R.id.previewView);
        setupOrientationListener();
        btnCapture = findViewById(R.id.btnCapture);
        btnCamSettings = findViewById(R.id.btnCamSettings);

        tvLatLng = findViewById(R.id.tvLatLng);
        tvAccuracy = findViewById(R.id.tvAccuracy);
        tvProject = findViewById(R.id.tvProject);
        tvDesc = findViewById(R.id.tvDesc);
        tvWatermarkLatLng = findViewById(R.id.tvWatermarkLatLng);
        tvDate = findViewById(R.id.tvDate);
        tvAddress = findViewById(R.id.tvAddress);

        viewAccuracyIndicator = findViewById(R.id.viewAccuracyIndicator);
        tvAccuracyBadge = findViewById(R.id.tvAccuracyBadge);
        projectRepo = new ProjectRepository(this);
        viewShutterFlash = findViewById(R.id.viewShutterFlash);
        if (viewShutterFlash != null) {
            viewShutterFlash.bringToFront();
            viewShutterFlash.setElevation(1000f);
        }

        applySiteFromIntentIfAny();

        ImageButton btnHome = findViewById(R.id.btnHome);
        btnHome.setOnClickListener(v -> goHome());

        ImageButton btnGalleryShortcut = findViewById(R.id.btnGalleryShortcut);
        btnGalleryShortcut.setOnClickListener(v ->
                startActivity(new Intent(this, ph.gov.geocamera.presentation.gallery.GalleryActivity.class))
        );

        setSiteLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                res -> {
                    isPickingSite = false; // ✅ ALWAYS reset

                    if (res.getResultCode() != RESULT_OK || res.getData() == null) {
                        // User cancelled/closed: don't force loop immediately
                        // Optional: show a toast or just keep capture disabled
                        updateCaptureAvailability();
                        return;
                    }

                    Intent data = res.getData();
                    String siteId = data.getStringExtra(SetSiteActivity.EXTRA_SITE_ID);
                    boolean uncat = data.getBooleanExtra(SetSiteActivity.EXTRA_UNCATEGORIZED, false);

                    // ✅ Save again here (extra-safe)
                    cameraPrefs.saveSite(siteId, uncat);

                    loadSiteFromPrefs();
                    updateOverlayTexts();
                    updateCaptureAvailability();
                }
        );

        btnCamSettings.setOnClickListener(v -> showSettingsMenu());
        btnCapture.setOnClickListener(v -> capturePhotoThenAskDesc());

        setupLocationPipelines();
        setupPermissions();
        requestNeededPermissions();

        btnCapture.setEnabled(false);
        btnCapture.setAlpha(0.35f);

        loadSiteFromPrefs();
        activeDescription = safeNull(cameraPrefs.getDescription());
        updateOverlayTexts();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            if (imageCapture != null && previewView != null && previewView.getDisplay() != null) {
                int rotation = previewView.getDisplay().getRotation();
                lastRotation = rotation;
                imageCapture.setTargetRotation(rotation);
            }
        } catch (Exception ignored) {}
    }

    private void applySiteFromIntentIfAny() {
        Intent intent = getIntent();
        if (intent == null) return;

        String siteId = intent.getStringExtra("siteId");
        if (siteId == null) return;

        siteId = siteId.trim();
        if (siteId.isEmpty()) return;

        cameraPrefs.saveSite(siteId, false);
        intent.removeExtra("siteId");

        loadSiteFromPrefs();

        if (tvProject != null) {
            updateOverlayTexts();
            updateCaptureAvailability();
        }
    }

    private void showSettingsMenu() {
        final String[] items = new String[] {
                "Change Site",
                allowIndoorFallback ? "Indoor Assist: ON" : "Indoor Assist: OFF"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Camera Settings")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        setSiteLauncher.launch(new Intent(this, SetSiteActivity.class));
                    } else if (which == 1) {
                        // ✅ Manual override only (Mode A default)
                        if (!allowIndoorFallback) {
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Enable Indoor Assist?")
                                    .setMessage("Indoor Assist uses assisted/network location when GPS fix is weak.\n\n" +
                                            "This may reduce accuracy. Captures will be marked as INDOOR_ASSIST in watermark/DB.")
                                    .setPositiveButton("Enable", (d, w) -> {
                                        allowIndoorFallback = true;
                                        cameraPrefs.saveIndoorAssistEnabled(true);
                                        Toast.makeText(this, "Indoor Assist ON", Toast.LENGTH_SHORT).show();
                                        updateCaptureAvailability();
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        } else {
                            allowIndoorFallback = false;
                            cameraPrefs.saveIndoorAssistEnabled(false);
                            Toast.makeText(this, "Indoor Assist OFF (GPS-Only)", Toast.LENGTH_SHORT).show();
                            updateCaptureAvailability();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void goHome() {
        Intent i = new Intent(this, ph.gov.geocamera.presentation.home.HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }
    private void setupOrientationListener() {
        orientationListener = new android.view.OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (previewView == null || previewView.getDisplay() == null) return;

                int rotation = previewView.getDisplay().getRotation();
                if (rotation == lastRotation) return;

                lastRotation = rotation;

                try {
                    if (imageCapture != null) imageCapture.setTargetRotation(rotation);
                    if (previewUseCase != null) previewUseCase.setTargetRotation(rotation);
                } catch (Exception ignored) {}
            }
        };
    }
    private void setupPermissions() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                res -> {
                    boolean camOk = Boolean.TRUE.equals(res.get(Manifest.permission.CAMERA));
                    boolean locOk = Boolean.TRUE.equals(res.get(Manifest.permission.ACCESS_FINE_LOCATION));

                    if (!camOk) {
                        Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }

                    startCamera();
                    loadSiteFromPrefs();

                    updateOverlayTexts();

                    enforceSiteSelectionFirstOnly();

                    if (locOk) startLiveLocation();
                    else {
                        showLocationUnavailable();
                        updateCaptureAvailability();
                    }
                }
        );
    }

    private void requestNeededPermissions() {
        permissionLauncher.launch(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION
        });
    }

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void postProcessAndSaveToDb(
            File file,
            String uuid,
            String groupId,
            String siteId,
            String project,
            String userId,
            Location captureLoc,
            String description
    ) {

        final String finalDescription = safeNull(description);

        if (finalDescription == null) {
            if (file.exists()) file.delete();

            runOnUiThread(() -> {
                Toast.makeText(this, "Description required. Photo discarded.", Toast.LENGTH_SHORT).show();
                isCapturing = false;
                updateCaptureAvailability();
            });
            return;
        }

        final Double lat = captureLoc.getLatitude();
        final Double lng = captureLoc.getLongitude();
        final Double accD = (double) captureLoc.getAccuracy();

        new Thread(() -> {
            try {

                String addressText = getProvinceCityBlocking(lat, lng);
                String modeToken = allowIndoorFallback ? "INDOOR_ASSIST" : "GPS_ONLY";

                String androidIdFromDb = getAndroidIdFromTblUsers();
                String capturedBy = getFullNameFromTblUsers();

                String watermarkSiteLabel = siteId;
                if (siteId != null && !"UNCAT".equalsIgnoreCase(siteId)) {
                    String coda = projectRepo.getProjectCodaById(siteId);
                    if (coda != null && !coda.trim().isEmpty()) {
                        watermarkSiteLabel = coda.trim();
                    }
                }

                burnWatermarkMinimalTopBarcode(
                        file,
                        project,
                        watermarkSiteLabel,
                        groupId,
                        uuid,
                        userId,
                        androidIdFromDb,
                        lat, lng, accD,
                        addressText,
                        finalDescription,
                        captureLoc,
                        capturedBy,
                        modeToken
                );

                imageRepo.insertImageMeta(
                        uuid,
                        groupId,
                        siteId,
                        userId,
                        lat,
                        lng,
                        accD,
                        addressText,
                        finalDescription,
                        modeToken,
                        project,
                        file.getAbsolutePath()
                );

                SyncScheduler.enqueueUploadNow(GeoCameraActivity.this);

                int count = imageRepo.countBySite(siteId);
                siteRepo.updateImageCount(siteId, count);

                runOnUiThread(() -> {
                    Toast.makeText(this, "Photo saved successfully.", Toast.LENGTH_SHORT).show();
                    isCapturing = false;
                    updateCaptureAvailability();
                });

            } catch (Exception e) {
                e.printStackTrace();
                isCapturing = false;
                updateCaptureAvailability();
            }
        }).start();
    }
    private void enforceSiteSelectionFirstOnly() {
        if (isPickingSite) return; // ✅ prevent repeat open

        if (!cameraPrefs.hasSelection()) {
            isPickingSite = true;
            setSiteLauncher.launch(new Intent(this, SetSiteActivity.class));
        }
    }

    private void capturePhotoThenAskDesc() {

        if (isCapturing) return;

        if (!cameraPrefs.hasSelection()) {
            enforceSiteSelectionFirstOnly();
            return;
        }

        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready.", Toast.LENGTH_SHORT).show();
            return;
        }

        final Location captureLoc = chooseCaptureLocation();
        if (captureLoc == null) {
            Toast.makeText(this, "Waiting for location...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!allowIndoorFallback) {
            if (gpsLastLocation == null || !isFresh(gpsLastLocation)) {
                Toast.makeText(this, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show();
                return;
            }
            if (gnssUsedInFix < MIN_USED_SATS) {
                Toast.makeText(this, "No GNSS fix yet.", Toast.LENGTH_LONG).show();
                return;
            }
            if (gpsLastLocation.getAccuracy() > OUTDOOR_OK_ACC) {
                Toast.makeText(this, "GPS weak (±" + Math.round(gpsLastLocation.getAccuracy()) + "m).", Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            if (!isFreshEither(captureLoc) || captureLoc.getAccuracy() > INDOOR_MAX_ACC) {
                Toast.makeText(this, "Location too weak (±" + Math.round(captureLoc.getAccuracy()) + "m).", Toast.LENGTH_LONG).show();
                return;
            }
        }

        isCapturing = true;
        btnCapture.setEnabled(false);
        btnCapture.setAlpha(0.35f);
        playCaptureAnimation();

        final String project = safe(userRepo.getProject(), "PROJECT");
        final String userId = safe(userRepo.getUserId(), "UNKNOWN");
        final String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date());
        final String motherFolder = project + "_" + year;
        final String siteId = (activeSiteId == null) ? "UNCAT" : activeSiteId;
        final String sessionDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        final String groupId = groupRepo.getOrCreateGroup(motherFolder, siteId, sessionDate, null);

        final File base = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (base == null) {
            isCapturing = false;
            updateCaptureAvailability();
            return;
        }

        String folderRel = groupRepo.getFolderNameByGroupId(groupId);
        if (folderRel == null || folderRel.trim().isEmpty()) {
            folderRel = motherFolder + "/" + siteId + "/" + sessionDate;
        }

        final File folder = new File(base, folderRel);
        if (!folder.exists()) folder.mkdirs();

        final String uuid = UUID.randomUUID().toString();
        final File file = new File(folder, "IMG_" + uuid + ".jpg");

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(file).build();

        imageCapture.takePicture(options, mainExecutor,
                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {

                        promptPhotoDescription(true, desc -> {
                            postProcessAndSaveToDb(
                                    file,
                                    uuid,
                                    groupId,
                                    siteId,
                                    project,
                                    userId,
                                    captureLoc,
                                    desc
                            );
                        });
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        isCapturing = false;
                        updateCaptureAvailability();
                    }
                });
    }
    private interface DescCallback {
        void onDesc(String desc);
    }

    private void promptPhotoDescription(boolean required, @NonNull DescCallback onDone) {

        TextInputLayout til = new TextInputLayout(this);
        til.setHint("Photo Description (e.g. Front view, Engine, Serial no.)");
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

        TextInputEditText et = new TextInputEditText(this);
        et.setSingleLine(true);

        // ✅ NOT ALL CAPS while typing
        // Optional: auto-capitalize sentences (nice UX)
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        til.addView(et);

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle("Photo Description")
                .setMessage("Enter description for this photo.")
                .setView(til)
                .setPositiveButton("OK", null)
                .setNegativeButton(required ? null : "Cancel", null)
                .setCancelable(!required);

        final androidx.appcompat.app.AlertDialog dialog = b.show();

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String desc = (et.getText() == null) ? "" : et.getText().toString().trim();

                    if (required && desc.isEmpty()) {
                        til.setError("Description is required.");
                        return;
                    }

                    til.setError(null);
                    dialog.dismiss();
                    onDone.onDesc(desc);
                });
    }


    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                int rotation = (previewView != null && previewView.getDisplay() != null)
                        ? previewView.getDisplay().getRotation()
                        : Surface.ROTATION_0;

                lastRotation = rotation;

                previewUseCase = new Preview.Builder()
                        .setTargetRotation(rotation)
                        .build();
                previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setTargetRotation(rotation)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(92)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        imageCapture
                );

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Camera failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, mainExecutor);
    }

    // =========================
    // LOCATION PIPELINES
    // =========================

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            int used = 0;
            int total = status.getSatelliteCount();
            for (int i = 0; i < total; i++) {
                if (status.usedInFix(i)) used++;
            }
            gnssUsedInFix = used;
            gnssTotal = total;
            lastGnssAt = System.currentTimeMillis();
            runOnUiThread(() -> updateCaptureAvailability());
        }
    };

    private final LocationListener gpsListener = new LocationListener() {

        @Override
        public void onLocationChanged(@NonNull Location loc) {
            // Basic mock reject (best effort)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && loc.isMock()) return;

            gpsLastLocation = loc;

            // ✅ reset stale best before comparing
            pruneBestGpsIfStale();

            if (gpsBestLocation == null || loc.getAccuracy() < gpsBestLocation.getAccuracy()) {
                gpsBestLocation = loc;
            }

            // UI shows best available (GPS preferred)
            lastLocation = chooseDisplayLocation();
            runOnUiThread(() -> {
                if (lastLocation != null) updateOverlay(lastLocation);
                updateCaptureAvailability();
            });
        }

        // ✅ REQUIRED for some devices/Android versions (even if deprecated)
        @Override
        @Deprecated
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // no-op (keep to avoid AbstractMethodError)
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            runOnUiThread(() -> updateCaptureAvailability());
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            runOnUiThread(() -> {
                showLocationUnavailable();
                updateCaptureAvailability();
            });
        }
    };
    private void setupLocationPipelines() {

        // Indoor assist (fused) request
        fusedRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(800)
                .setMaxUpdateDelayMillis(0)
                .setWaitForAccurateLocation(false)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .build();

        fusedCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;

                fusedLastLocation = loc;

                // Only use fused for UI/capture when indoor assist is ON
                if (allowIndoorFallback) {
                    lastLocation = chooseDisplayLocation();
                    runOnUiThread(() -> {
                        if (lastLocation != null) updateOverlay(lastLocation);
                        updateCaptureAvailability();
                    });
                }
            }
        };
    }

    private void startLiveLocation() {
        if (!hasFineLocation()) return;

        // Start GPS provider (strict always)
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                showLocationUnavailable();
                updateCaptureAvailability();
                Toast.makeText(this, "Please enable GPS.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                return;
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            // GNSS status
            try {
                locationManager.registerGnssStatusCallback(gnssCallback, new android.os.Handler(Looper.getMainLooper()));
            } catch (Exception ignored) {}

            // GPS updates
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    gpsListener,
                    Looper.getMainLooper()
            );

            // Seed last known GPS
            Location lk = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lk != null) {
                gpsLastLocation = lk;

                pruneBestGpsIfStale(); // ✅ add

                if (gpsBestLocation == null || lk.getAccuracy() < gpsBestLocation.getAccuracy()) {
                    gpsBestLocation = lk;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            showLocationUnavailable();
            updateCaptureAvailability();
        }

        // Start fused only for indoor assist (kept running; you can stop it when OFF if you want)
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            fusedLocationClient.requestLocationUpdates(fusedRequest, fusedCallback, Looper.getMainLooper());
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(loc -> {
                        if (loc != null) fusedLastLocation = loc;
                        lastLocation = chooseDisplayLocation();
                        if (lastLocation != null) updateOverlay(lastLocation);
                        updateCaptureAvailability();
                    });
        } catch (Exception ignored) {}

        lastLocation = chooseDisplayLocation();
        if (lastLocation != null) updateOverlay(lastLocation);
        updateCaptureAvailability();
    }

    private void stopLiveLocation() {
        try {
            if (locationManager != null) {
                locationManager.removeUpdates(gpsListener);
                try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        try {
            if (fusedLocationClient != null && fusedCallback != null) {
                fusedLocationClient.removeLocationUpdates(fusedCallback);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();

        applySiteFromIntentIfAny();

        loadSiteFromPrefs();

        updateOverlayTexts();
        if (!isPickingSite) {
            enforceSiteSelectionFirstOnly();
        }

        // ✅ enable orientation listener
        if (orientationListener != null && orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }

        if (hasFineLocation()) startLiveLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // ✅ disable orientation listener
        if (orientationListener != null) {
            orientationListener.disable();
        }

        stopLiveLocation();
    }

    // Prefer GPS for display; if indoor assist ON and GPS weak/stale, show fused
    private Location chooseDisplayLocation() {
        Location gps = gpsLastLocation;
        if (gps != null && isFresh(gps)) return gps;

        if (allowIndoorFallback) {
            Location f = fusedLastLocation;
            if (f != null && isFreshFused(f)) return f;
        }
        return (gps != null) ? gps : fusedLastLocation;
    }

    private void updateOverlay(@NonNull Location loc) {
        tvLatLng.setText(String.format(Locale.US,
                "Lat: %.6f Lng: %.6f",
                loc.getLatitude(), loc.getLongitude()));

        tvAccuracy.setText(String.format(Locale.US,
                "Accuracy: ±%.1f m", loc.getAccuracy()));

        updateAccuracyBadge(loc);
        updateOverlayTexts();
    }

    private void showLocationUnavailable() {
        tvLatLng.setText("Lat: --, Lng: --");
        tvAccuracy.setText("Accuracy: -- m");
        setBadge(Color.GRAY, "GPS --");
        updateOverlayTexts();
    }

    private void updateAccuracyBadge(Location location) {
        if (location == null) {
            setBadge(Color.GRAY, "GPS --");
            return;
        }

        float acc = location.getAccuracy();

        boolean gpsFresh = isFresh(gpsLastLocation);
        boolean gnssFresh = (System.currentTimeMillis() - lastGnssAt) < 10_000;
        String sats = gnssFresh ? (" SATS " + gnssUsedInFix + "/" + gnssTotal) : " SATS --/--";
        String label = String.format(Locale.US, "±%.0fm", acc);

        // If GPS present, show GPS state
        if (gpsFresh && gpsLastLocation != null) {
            float gpsAcc = gpsLastLocation.getAccuracy();
            String gpsLabel = String.format(Locale.US, "±%.0fm", gpsAcc);

            if (gnssUsedInFix < MIN_USED_SATS) {
                setBadge(Color.rgb(255, 140, 0), "NO FIX " + gpsLabel + sats);
                return;
            }

            if (gpsAcc <= OUTDOOR_OK_ACC) {
                setBadge(Color.GREEN, "GPS LOCK " + gpsLabel + sats);
                return;
            }

            // GPS but weak
            if (allowIndoorFallback) {
                setBadge(Color.CYAN, "INDOOR_ASSIST " + label);
            } else {
                setBadge(Color.RED, "GPS WEAK " + gpsLabel + sats);
            }
            return;
        }

        // No fresh GPS
        if (allowIndoorFallback && fusedLastLocation != null && isFreshFused(fusedLastLocation)) {
            setBadge(Color.CYAN, "INDOOR_ASSIST " + label);
            return;
        }

        setBadge(Color.GRAY, "STALE " + label + sats);
    }

    private void setBadge(int color, String text) {
        viewAccuracyIndicator.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(color)
        );
        tvAccuracyBadge.setText(text);
    }

    // ✅ prune stale "best" sample
    private void pruneBestGpsIfStale() {
        if (gpsBestLocation != null && !isFresh(gpsBestLocation)) {
            gpsBestLocation = null;
        }
    }

    private void updateCaptureAvailability() {

        pruneBestGpsIfStale();

        if (!cameraPrefs.hasSelection()) {
            firstGoodFixAt = 0L;
            stableFixCount = 0; // legacy
            disableCapture();
            if (tvCaptureStatus != null) tvCaptureStatus.setText("SELECT SITE");
            return;
        }

        // ✅ Mode A: GPS-only strict (unless indoor assist ON)
        if (!allowIndoorFallback) {

            Location gps = gpsLastLocation;

            if (gps == null) {
                firstGoodFixAt = 0L;
                disableCapture();
                if (tvCaptureStatus != null) tvCaptureStatus.setText("WAIT GPS");
                return;
            }

            if (!isFresh(gps)) {
                firstGoodFixAt = 0L;
                disableCapture();
                if (tvCaptureStatus != null) tvCaptureStatus.setText("STALE");
                return;
            }

            if (gnssUsedInFix < MIN_USED_SATS) {
                firstGoodFixAt = 0L;
                disableCapture();
                if (tvCaptureStatus != null) tvCaptureStatus.setText("NO FIX (" + gnssUsedInFix + ")");
                return;
            }

            float acc = gps.getAccuracy();

            if (acc <= OUTDOOR_OK_ACC) {
                if (firstGoodFixAt == 0L) firstGoodFixAt = System.currentTimeMillis();

                boolean allow = (System.currentTimeMillis() - firstGoodFixAt) >= REQUIRED_GOOD_MS;

                btnCapture.setEnabled(allow);
                btnCapture.setAlpha(allow ? 1f : 0.35f);

                if (tvCaptureStatus != null) {
                    tvCaptureStatus.setText(allow ? "GPS LOCK" : "STABILIZING");
                }
            } else {
                firstGoodFixAt = 0L;
                disableCapture();
                if (tvCaptureStatus != null) tvCaptureStatus.setText("GPS WEAK");
            }
            return;
        }

        // ✅ Indoor Assist ON: allow fused/network within cap
        Location capture = chooseCaptureLocation();
        if (capture == null) {
            stableFixCount = 0;
            disableCapture();
            if (tvCaptureStatus != null) tvCaptureStatus.setText("WAIT LOC");
            return;
        }

        if (!isFreshEither(capture)) {
            stableFixCount = 0;
            disableCapture();
            if (tvCaptureStatus != null) tvCaptureStatus.setText("STALE");
            return;
        }

        float acc = capture.getAccuracy();
        boolean allow = acc <= INDOOR_MAX_ACC;
        btnCapture.setEnabled(allow);
        btnCapture.setAlpha(allow ? 1f : 0.35f);
        if (tvCaptureStatus != null) tvCaptureStatus.setText(allow ? "INDOOR_ASSIST" : "WEAK");
    }

    private void disableCapture() {
        btnCapture.setEnabled(false);
        btnCapture.setAlpha(0.35f);
    }

    private void updateOverlayTexts() {
        String project = safe(userRepo.getProject(), "PROJECT");

        String siteLabel;
        if (activeSiteId == null) {
            siteLabel = "UNCAT";
        } else {
            String coda = projectRepo.getProjectCodaById(activeSiteId);
            siteLabel = safe(coda, activeSiteId);
        }

        if (tvProject != null) {
            tvProject.setText(project + " | " + siteLabel);
        }

        String desc = safeNull(cameraPrefs.getDescription());
        if (tvDesc != null) {
            tvDesc.setText(desc == null ? "N/A" : desc);
        }

        if (tvDate != null) {
            String dateStr = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(new Date());
            tvDate.setText(dateStr);
        }

        Location loc = lastLocation;

        if (loc != null) {
            if (tvWatermarkLatLng != null) {
                String elevText = "--";

                if (loc.hasAltitude()) {
                    elevText = String.format(Locale.US, "%.1f m", loc.getAltitude());
                }

                tvWatermarkLatLng.setText(String.format(Locale.US,
                        "Lat: %.6f | Lng: %.6f | Elev: %s",
                        loc.getLatitude(),
                        loc.getLongitude(),
                        elevText
                ));
            }
            updateAddressFromLocation(loc);
        } else {
            if (tvWatermarkLatLng != null) tvWatermarkLatLng.setText("Lat: -- | Lng: --");
            if (tvAddress != null) tvAddress.setText("Province");
        }
    }
    private String getSelectedSiteIdOrUncat() {
        return (activeSiteId == null || activeSiteId.trim().isEmpty()) ? "UNCAT" : activeSiteId.trim();
    }

    private String getSelectedProjectIdForUpload() {
        if (activeUncategorized || activeSiteId == null || activeSiteId.trim().isEmpty()) {
            return "";
        }

        String pid = siteRepo.getProjectIdBySiteId(activeSiteId);
        return pid == null ? "" : pid.trim();
    }

    private String getSelectedProjectLabelForDisplay() {
        if (activeUncategorized || activeSiteId == null || activeSiteId.trim().isEmpty()) {
            return "UNCAT";
        }

        String label = siteRepo.getProjectLabelBySiteId(activeSiteId);
        if (label != null && !label.trim().isEmpty()) return label.trim();

        return activeSiteId.trim();
    }
    private void loadSiteFromPrefs() {
        if (cameraPrefs.hasSelection()) {
            activeUncategorized = cameraPrefs.isUncategorized();
            activeSiteId = activeUncategorized ? null : safeNull(cameraPrefs.getSiteId());

            if (!activeUncategorized && activeSiteId != null) {
                activeProjectId = safeNull(siteRepo.getProjectIdBySiteId(activeSiteId));
                activeProjectLabel = safeNull(siteRepo.getProjectLabelBySiteId(activeSiteId));
            } else {
                activeProjectId = null;
                activeProjectLabel = null;
            }
        } else {
            activeUncategorized = true;
            activeSiteId = null;
            activeProjectId = null;
            activeProjectLabel = null;
        }
    }
    private void updateAddressFromLocation(@NonNull Location loc) {
        if (tvAddress == null) return;

        long now = System.currentTimeMillis();

        double dLat = Math.abs(loc.getLatitude() - lastGeoLat);
        double dLng = Math.abs(loc.getLongitude() - lastGeoLng);
        boolean movedEnough = (dLat + dLng) > 0.0007;

        // throttle if not moved much
        if (!movedEnough && (now - lastGeocodeAt) < 6000) {
            tvAddress.setText(lastAddressText);
            return;
        }

        lastGeocodeAt = now;
        lastGeoLat = loc.getLatitude();
        lastGeoLng = loc.getLongitude();

        // ✅ always prepare offline fallback first
        final String offline = tryOfflineProvinceCity(loc.getLatitude(), loc.getLongitude());
        final String offlineSafe = (offline != null && !offline.trim().isEmpty()) ? offline.trim() : "Province";

        Geocoder geocoder = new Geocoder(this, Locale.getDefault());

        // If no geocoder implementation at all, use offline immediately
        if (!Geocoder.isPresent()) {
            lastAddressText = offlineSafe;
            tvAddress.setText(lastAddressText);
            return;
        }

        // -------- Android 13+ callback style --------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1, addresses -> {

                String txt = formatProvinceCity(addresses);

                // ✅ if geocoder returns empty / placeholder / same old value -> fallback
                if (txt == null) txt = "";
                txt = txt.trim();

                boolean bad =
                        txt.isEmpty()
                                || txt.equalsIgnoreCase("Province")
                                || txt.equalsIgnoreCase("Province, City")
                                || txt.equalsIgnoreCase(lastAddressText);

                final String finalTxt = bad ? offlineSafe : txt;

                runOnUiThread(() -> {
                    lastAddressText = finalTxt;
                    if (tvAddress != null) tvAddress.setText(finalTxt);
                });
            });
            return;
        }

        // -------- Below Android 13: blocking call in background thread --------
        new Thread(() -> {
            String txt = "";
            try {
                List<Address> addresses = geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                String t = formatProvinceCity(addresses);
                if (t != null) txt = t.trim();
            } catch (Exception ignored) {}

            boolean bad =
                    txt.isEmpty()
                            || txt.equalsIgnoreCase("Province")
                            || txt.equalsIgnoreCase("Province, City")
                            || txt.equalsIgnoreCase(lastAddressText);

            final String finalTxt = bad ? offlineSafe : txt;

            runOnUiThread(() -> {
                lastAddressText = finalTxt;
                if (tvAddress != null) tvAddress.setText(finalTxt);
            });
        }).start();
    }
    private String tryOfflineProvinceCity(double lat, double lng) {
        try {
            // ✅ EASY fallback: province only (hardcoded centroids)
            return ph.gov.geocamera.core.utils.ProvinceLookup.getNearestProvince(lat, lng);
        } catch (Exception ignored) {}
        return null;
    }
    private String formatProvinceCity(List<Address> addresses) {
        if (addresses == null || addresses.isEmpty()) return "";

        Address a = addresses.get(0);
        String province = a.getAdminArea();
        String city = a.getLocality();

        if (city == null || city.trim().isEmpty()) city = a.getSubAdminArea();
        if (province == null || province.trim().isEmpty()) province = a.getSubAdminArea();
        if (province == null || province.trim().isEmpty()) province = a.getCountryName();

        province = province == null ? "" : province.trim();
        city = city == null ? "" : city.trim();

        if (!province.isEmpty() && !city.isEmpty()) return province + ", " + city;
        if (!city.isEmpty()) return city;
        if (!province.isEmpty()) return province;

        return "";
    }

    private String getProvinceCityBlocking(double latitude, double longitude) {

        // 1) Online/Geocoder attempt (best effort)
        try {
            if (Geocoder.isPresent()) {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> list = geocoder.getFromLocation(latitude, longitude, 1);

                if (list != null && !list.isEmpty()) {
                    Address a = list.get(0);

                    String province = a.getAdminArea();
                    String city = a.getLocality();

                    if (city == null || city.trim().isEmpty()) city = a.getSubAdminArea();
                    if (province == null || province.trim().isEmpty()) province = a.getSubAdminArea();
                    if (province == null || province.trim().isEmpty()) province = a.getCountryName();

                    province = (province == null) ? "" : province.trim();
                    city = (city == null) ? "" : city.trim();

                    if (!province.isEmpty() && !city.isEmpty()) return province + ", " + city;
                    if (!city.isEmpty()) return city;
                    if (!province.isEmpty()) return province;
                }
            }
        } catch (Exception ignored) {}

        // 2) Offline fallback: province-only
        String offline = tryOfflineProvinceCity(latitude, longitude);
        if (offline != null) return offline;

        return "Province";
    }

    // =========================
    // CAPTURE (GPS-only default; Indoor Assist marks mode)
    // =========================
    private void capturePhoto(String description) {

        if (isCapturing) return;

        final String finalDescription = safeNull(description);
        if (finalDescription == null) {
            cameraPrefs.clearDescription();
            activeDescription = null;
            updateOverlayTexts();
            Toast.makeText(this, "Description is required.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!cameraPrefs.hasSelection()) {
            enforceSiteSelectionFirstOnly();
            return;
        }

        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready.", Toast.LENGTH_SHORT).show();
            return;
        }

        final Location captureLoc = chooseCaptureLocation();
        if (captureLoc == null) {
            Toast.makeText(this, "Waiting for location...", Toast.LENGTH_SHORT).show();
            return;
        }

        // Strict checks
        if (!allowIndoorFallback) {
            if (gpsLastLocation == null || !isFresh(gpsLastLocation)) {
                Toast.makeText(this, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show();
                return;
            }
            if (gnssUsedInFix < MIN_USED_SATS) {
                Toast.makeText(this, "No GNSS fix yet. Go outdoor / open sky.", Toast.LENGTH_LONG).show();
                return;
            }
            if (gpsLastLocation.getAccuracy() > OUTDOOR_OK_ACC) {
                Toast.makeText(this, "GPS weak (±" + Math.round(gpsLastLocation.getAccuracy()) + "m). Move to open sky.", Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            // Indoor assist allowed: cap at INDOOR_MAX_ACC
            if (!isFreshEither(captureLoc) || captureLoc.getAccuracy() > INDOOR_MAX_ACC) {
                Toast.makeText(this, "Location too weak (±" + Math.round(captureLoc.getAccuracy()) + "m).", Toast.LENGTH_LONG).show();
                return;
            }
        }

        isCapturing = true;
        btnCapture.setEnabled(false);
        btnCapture.setAlpha(0.35f);
        playCaptureAnimation();

        final String project = safe(userRepo.getProject(), "PROJECT");
        final String userId = safe(userRepo.getUserId(), "UNKNOWN");

        final String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date());
        final String motherFolder = project + "_" + year;

        final String siteId = (activeSiteId == null) ? "UNCAT" : activeSiteId;
        final String sessionDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        final String groupId = groupRepo.getOrCreateGroup(motherFolder, siteId, sessionDate, "GENERAL");

        final File base = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (base == null) {
            isCapturing = false;
            Toast.makeText(this, "Storage unavailable.", Toast.LENGTH_LONG).show();
            updateCaptureAvailability();
            return;
        }

        String folderRel = groupRepo.getFolderNameByGroupId(groupId);
        if (folderRel == null || folderRel.trim().isEmpty()) {
            folderRel = motherFolder + "/" + siteId + "/" + sessionDate; // ✅ per date only
        }

        final File folder = new File(base, folderRel);
        if (!folder.exists() && !folder.mkdirs()) {
            isCapturing = false;
            Toast.makeText(this, "Failed to create folder.", Toast.LENGTH_LONG).show();
            updateCaptureAvailability();
            return;
        }

        try {
            File noMedia = new File(folder, ".nomedia");
            if (!noMedia.exists()) noMedia.createNewFile();
        } catch (Exception ignored) {}

        final String uuid = UUID.randomUUID().toString();
        final File file = new File(folder, "IMG_" + uuid + ".jpg");

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(file).build();

        imageCapture.takePicture(options, mainExecutor,
                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {

                        final Double lat = captureLoc.getLatitude();
                        final Double lng = captureLoc.getLongitude();
                        final Double accD = (double) captureLoc.getAccuracy();

                        new Thread(() -> {
                            try {
                                StringBuilder err = new StringBuilder();

                                String modeToken = allowIndoorFallback ? "INDOOR_ASSIST" : "GPS_ONLY";
                                appendErr(err, modeToken);

                                // Source labeling
                                if (!allowIndoorFallback) {
                                    appendErr(err, "GPS_LOCK");
                                    appendErr(err, "SATS_" + gnssUsedInFix + "_" + gnssTotal);
                                } else {
                                    appendErr(err, "ASSISTED");
                                }

                                appendErr(err, isFreshEither(captureLoc) ? "FRESH" : "STALE");

                                String addressText = getProvinceCityBlocking(lat, lng);
                                if (addressText == null || addressText.trim().isEmpty()
                                        || "Province, City".equalsIgnoreCase(addressText.trim())) {
                                    addressText = "Province, City";
                                    appendErr(err, "GEOCODER_FAIL");
                                }

                                lastAddressText = addressText;

                                String androidIdFromDb = getAndroidIdFromTblUsers();
                                if (androidIdFromDb == null) androidIdFromDb = "";

                                String capturedBy = getFullNameFromTblUsers();

                                String watermarkSiteLabel = siteId;
                                if (siteId != null && !"UNCAT".equalsIgnoreCase(siteId)) {
                                    String coda = projectRepo.getProjectCodaById(siteId);
                                    if (coda != null && !coda.trim().isEmpty()) {
                                        watermarkSiteLabel = coda.trim();
                                    }
                                }

                                burnWatermarkMinimalTopBarcode(
                                        file,
                                        project,
                                        watermarkSiteLabel,
                                        groupId,
                                        uuid,
                                        userId,
                                        androidIdFromDb,
                                        lat, lng, accD,
                                        addressText,
                                        finalDescription,
                                        captureLoc,
                                        capturedBy,
                                        modeToken
                                );

                                String errorAtLoc = err.toString();

                                imageRepo.insertImageMeta(
                                        uuid,
                                        groupId,
                                        siteId,
                                        userId,
                                        lat,
                                        lng,
                                        accD,
                                        addressText,
                                        finalDescription, // ✅ per-photo description
                                        errorAtLoc,
                                        project,
                                        file.getAbsolutePath()
                                );

                                SyncScheduler.enqueueUploadNow(GeoCameraActivity.this);

                                int count = imageRepo.countBySite(siteId);
                                siteRepo.updateImageCount(siteId, count);

                                runOnUiThread(() -> {
                                    Toast.makeText(GeoCameraActivity.this,
                                            "Photo saved successfully.",
                                            Toast.LENGTH_SHORT).show();

                                    isCapturing = false;
                                    updateCaptureAvailability();
                                });

                            } catch (Exception ex) {
                                ex.printStackTrace();
                                runOnUiThread(() -> {
                                    Toast.makeText(GeoCameraActivity.this,
                                            "Post-process failed: " + ex.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                    isCapturing = false;
                                    updateCaptureAvailability();
                                });
                            }
                        }).start();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        runOnUiThread(() -> {
                            Toast.makeText(GeoCameraActivity.this,
                                    "Capture failed: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();

                            isCapturing = false;
                            updateCaptureAvailability();
                        });
                    }
                });
    }

    private boolean isFresh(Location l) {
        if (l == null) return false;
        long ageMs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            ageMs = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
        } else {
            ageMs = System.currentTimeMillis() - l.getTime();
        }
        return ageMs >= 0 && ageMs < GPS_FRESH_MS;
    }

    private boolean isFreshFused(Location l) {
        if (l == null) return false;
        long ageMs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            ageMs = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
        } else {
            ageMs = System.currentTimeMillis() - l.getTime();
        }
        // allow slightly older fused but still reasonable
        return ageMs >= 0 && ageMs < 12_000;
    }

    // ✅ FIXED provider check (no identity-compare bug)
    private boolean isFreshEither(Location l) {
        if (l == null) return false;

        String p = l.getProvider();
        if (p != null && p.equalsIgnoreCase(LocationManager.GPS_PROVIDER)) {
            return isFresh(l);      // strict for GPS
        }
        return isFreshFused(l);     // slightly looser for fused/network
    }

    private Location chooseCaptureLocation() {

        // ✅ Strict GPS-only mode
        if (!allowIndoorFallback) {
            if (gpsLastLocation != null && isFresh(gpsLastLocation) && gnssUsedInFix >= MIN_USED_SATS) {
                // Prefer best accuracy sample seen (fresh only)
                if (gpsBestLocation != null
                        && isFresh(gpsBestLocation)
                        && gpsBestLocation.getAccuracy() <= gpsLastLocation.getAccuracy()) {
                    return gpsBestLocation;
                }
                return gpsLastLocation;
            }
            return null;
        }

        // ✅ Indoor assist ON: prefer GPS if it qualifies; else accept fused within cap
        if (gpsLastLocation != null
                && isFresh(gpsLastLocation)
                && gnssUsedInFix >= MIN_USED_SATS
                && gpsLastLocation.getAccuracy() <= OUTDOOR_OK_ACC) {
            if (gpsBestLocation != null && isFresh(gpsBestLocation)) return gpsBestLocation;
            return gpsLastLocation;
        }

        if (fusedLastLocation != null && isFreshFused(fusedLastLocation) && fusedLastLocation.getAccuracy() <= INDOOR_MAX_ACC) {
            return fusedLastLocation;
        }

        // If GPS exists but weak, still allow if within indoor cap (and will be marked)
        if (gpsLastLocation != null && isFresh(gpsLastLocation) && gpsLastLocation.getAccuracy() <= INDOOR_MAX_ACC) {
            return gpsLastLocation;
        }

        return null;
    }

    private void playCaptureAnimation() {
        if (viewShutterFlash == null) return;

        viewShutterFlash.clearAnimation();
        viewShutterFlash.bringToFront();
        viewShutterFlash.setVisibility(View.VISIBLE);
        viewShutterFlash.setAlpha(0f);

        viewShutterFlash.animate()
                .alpha(0.95f)
                .setDuration(45)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> viewShutterFlash.animate()
                        .alpha(0f)
                        .setDuration(180)
                        .withEndAction(() -> viewShutterFlash.setVisibility(View.GONE))
                        .start())
                .start();
    }

    private static void appendErr(StringBuilder sb, String token) {
        if (token == null || token.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append(";");
        sb.append(token.trim());
    }

    private String getAndroidIdFromTblUsers() {
        Cursor c = null;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            c = db.rawQuery("SELECT android_id FROM tbl_users ORDER BY timestamp DESC LIMIT 1", null);
            if (c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }

        try {
            String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            return (id == null) ? "" : id.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String getFullNameFromTblUsers() {
        Cursor c = null;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            c = db.rawQuery(
                    "SELECT fname, lname FROM tbl_users ORDER BY timestamp DESC LIMIT 1",
                    null
            );

            if (c.moveToFirst()) {
                String fn = c.getString(0);
                String ln = c.getString(1);

                fn = (fn == null) ? "" : fn.trim();
                ln = (ln == null) ? "" : ln.trim();

                String full = (fn + " " + ln).trim();
                return full.isEmpty() ? "Unknown" : full;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "Unknown";
    }

    // =========================================================================================
    // UPDATED: Watermark + preserve EXIF + add GPS EXIF + write SIG into EXIF UserComment
    // + MODE token (GPS_ONLY / INDOOR_ASSIST)
    // =========================================================================================
    private void burnWatermarkMinimalTopBarcode(File file,
                                                String project,
                                                String siteLabel,
                                                String groupId,
                                                String uuid,
                                                String userId,
                                                String androidId,
                                                Double lat,
                                                Double lng,
                                                Double acc,
                                                String provinceCity,
                                                String descriptionForQr,
                                                Location captureLocForExif,
                                                String capturedBy,
                                                String modeToken) {

        ExifSnapshot snap = null;
        try { snap = ExifSnapshot.read(file); } catch (Exception ignored) {}

        try {
            Bitmap original = decodeScaled(file.getAbsolutePath(), 3200, 3200);
            if (original == null) return;

            Bitmap mutable = original.copy(Bitmap.Config.ARGB_8888, true);
            if (mutable == null) return;

            Canvas canvas = new Canvas(mutable);

            int w = mutable.getWidth();
            int h = mutable.getHeight();

            int pad = Math.max(18, w / 70);

            Bitmap companyLogo = BitmapFactory.decodeResource(getResources(), R.drawable.company_logo);

            // =========================
            // Text sizes (responsive)
            // =========================
            float titleSize = Math.max(28f, w / 42f);
            float bodySize  = Math.max(24f, w / 50f);
            float smallSize = Math.max(20f, w / 58f);

            Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
            pTitle.setTextSize(titleSize);
            Paint.FontMetrics fmTitle = pTitle.getFontMetrics();

            Paint pBody = new Paint(Paint.ANTI_ALIAS_FLAG);
            pBody.setTextSize(bodySize);
            Paint.FontMetrics fmBody = pBody.getFontMetrics();

            Paint pSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
            pSmall.setTextSize(smallSize);
            Paint.FontMetrics fmSmall = pSmall.getFontMetrics();

            float titleStep = (-fmTitle.ascent);
            float bodyStep  = (-fmBody.ascent);
            float smallStep = (-fmSmall.ascent);

            // Tight gaps
            float bodyGap  = Math.max(3f, bodyStep  * 0.10f);
            float smallGap = Math.max(2f, smallStep * 0.08f);

            // =========================
            // Barcode sizes
            // =========================
            int barcodeW = clamp((int) (w * 0.72f), 520, w - (pad * 2));
            int barcodeH = clamp(h / 26, 90, 170);

            String datePretty = new SimpleDateFormat("MMMM dd, yyyy", Locale.US).format(new Date());
            String timePretty = new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date());
            String capturedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

            String barcodeValue = uuid;

            // For signature (kept as-is, you already store it in EXIF)
            String canonicalForSig = buildCanonicalForSig(
                    uuid, project, siteLabel, descriptionForQr, lat, lng, acc, provinceCity, capturedAt
            );

            String signature = "";
            try { signature = hmacSha256Base64Url(QR_HMAC_SECRET, canonicalForSig); }
            catch (Exception ignored) {}

            Bitmap barcodeBmp = createBarcodeBitmap(barcodeValue, barcodeW, barcodeH);

            // =========================
            // Top: Barcode
            // =========================
            if (barcodeBmp != null) {
                float bx = (w - barcodeW) / 2f;
                float by = pad;
                canvas.drawBitmap(barcodeBmp, bx, by, null);
            }

            // =========================
            // "by: GeoKlik" under QR
            // =========================
            float byTextSize = Math.max(16f, w / 95f);
            String byLabel = "by: GeoKlik";

            Paint byPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            byPaint.setTextSize(byTextSize);
            byPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
            Paint.FontMetrics byFm = byPaint.getFontMetrics();

            float labelH = (byFm.descent - byFm.ascent);
            float gapQrToLabel = 3f;

            // =========================
            // Build lines
            // =========================
            String line1 = safe(project, "PROJECT") + " | " + safe(siteLabel, "UNCAT");
            String line2 = (provinceCity == null || provinceCity.trim().isEmpty())
                    ? "Province, City" : provinceCity.trim();

            String latStr = (lat == null) ? "--" : String.format(Locale.US, "%.6f", lat);
            String lngStr = (lng == null) ? "--" : String.format(Locale.US, "%.6f", lng);

            String elevStr = "--";
            if (captureLocForExif != null && captureLocForExif.hasAltitude()) {
                elevStr = String.format(Locale.US, "%.1f m", captureLocForExif.getAltitude());
            }

            String latLine = "Lat: " + latStr;
            String lngLine = "Lng: " + lngStr;
            String elevLine = "Elev: " + elevStr;

            String oneLineLatLng = latLine + "  " + lngLine + "  " + elevLine;

            String line4 = datePretty + "  " + timePretty;

            String capName = safe(capturedBy, "Unknown")
                    .replace("|", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            String line5 = "Captured by: " + capName + " | " + safe(modeToken, "GPS_ONLY");

            // =========================
            // ✅ QR payload (NO UUID; UUID stays in barcode)
            // =========================
            String qrPayload =
                    "GEOKLIK V1\n" +
                            "Proj:" + safe(project, "-") + "\n" +
                            "Site:" + safe(siteLabel, "-") + "\n" +
                            "Loc:"  + safe(line2, "-") + "\n" +
                            "Lat:"  + latStr + "\n" +
                            "Lng:"  + lngStr + "\n" +
                            "Elev:" + elevStr + "\n" +
                            "At:"   + capturedAt + "\n" +
                            "Mode:" + safe(modeToken, "-");

            // =========================
            // Bottom row anchor (sagad)
            // =========================
            int safeBottom = pad;
            float bottomBandBottom = h - safeBottom;

            // =========================
            // Start with smaller QR (cap) para balanced
            // =========================
            int qrMin = 150;
            int qrMax = 280;                 // adjust if needed (260–320)
            int qrStart = clamp(w / 7, qrMin, qrMax);

            int qrSize = qrStart;
            int logoSize = qrSize; // exact match height

            Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
            measure.setTextSize(smallSize);

            // 2 passes: compute textBlockH => lock QR to text height
            for (int pass = 0; pass < 2; pass++) {

                int qrX = w - pad - qrSize;
                int logoX = pad;

                float leftTextX = logoX + logoSize + clamp(w / 45, 16, 24);
                float textRightLimit = qrX - clamp(w / 45, 16, 24);
                float maxTextWidth = Math.max(w * 0.30f, textRightLimit - leftTextX);

                boolean latLngOneLine = measure.measureText(oneLineLatLng) <= maxTextWidth;
                boolean line5Wraps = measure.measureText(line5) > maxTextWidth;

                float latLngH = latLngOneLine ? smallStep : (smallStep + smallGap + smallStep);
                float line5H  = line5Wraps ? (smallStep + smallGap + smallStep) : smallStep;

                float textBlockH =
                        titleStep +
                                bodyGap +
                                bodyStep +
                                smallGap +
                                latLngH +
                                smallGap +
                                smallStep +
                                (smallGap * 0.30f) +
                                line5H;

                int targetQr = (int) (textBlockH - (labelH + gapQrToLabel));
                targetQr = clamp(targetQr, qrMin, qrMax);

                qrSize = targetQr;
                logoSize = qrSize;
            }

            // Final positions after sizing
            int qrX = w - pad - qrSize;
            int logoX = pad;

            float leftTextX = logoX + logoSize + clamp(w / 45, 16, 24);
            float textRightLimit = qrX - clamp(w / 45, 16, 24);
            float maxTextWidth = Math.max(w * 0.30f, textRightLimit - leftTextX);

            boolean latLngOneLine = measure.measureText(oneLineLatLng) <= maxTextWidth;
            boolean line5Wraps = measure.measureText(line5) > maxTextWidth;

            float latLngH = latLngOneLine ? smallStep : (smallStep + smallGap + smallStep);
            float line5H  = line5Wraps ? (smallStep + smallGap + smallStep) : smallStep;

            float textBlockH =
                    titleStep +
                            bodyGap +
                            bodyStep +
                            smallGap +
                            latLngH +
                            smallGap +
                            smallStep +
                            (smallGap * 0.30f) +
                            line5H;

            float rowH = Math.max(textBlockH, (qrSize + gapQrToLabel + labelH));
            float rowTop = bottomBandBottom - rowH;

            // =========================
            // Draw QR (bottom aligned)
            // =========================
            Bitmap qrBmp = createQrBitmap(qrPayload, qrSize, qrSize);

            // Center logo small (scan-safe)
            if (qrBmp != null && companyLogo != null) {
                Bitmap outQr = qrBmp.copy(Bitmap.Config.ARGB_8888, true);
                Canvas qc = new Canvas(outQr);

                int qrW = outQr.getWidth();
                int qrHh = outQr.getHeight();

                int centerLogo = (int) (Math.min(qrW, qrHh) * 0.10f);
                centerLogo = Math.max(42, centerLogo);
                centerLogo = Math.min(centerLogo, (int) (Math.min(qrW, qrHh) * 0.14f));

                Bitmap scaledCenterLogo = Bitmap.createScaledBitmap(companyLogo, centerLogo, centerLogo, true);

                int cx = (qrW - centerLogo) / 2;
                int cy = (qrHh - centerLogo) / 2;

                float platePad = Math.max(8f, centerLogo * 0.20f);
                RectF plate = new RectF(
                        cx - platePad,
                        cy - platePad,
                        cx + centerLogo + platePad,
                        cy + centerLogo + platePad
                );

                Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                platePaint.setColor(Color.WHITE);
                qc.drawRoundRect(plate, 16f, 16f, platePaint);

                Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
                border.setStyle(Paint.Style.STROKE);
                border.setStrokeWidth(Math.max(2f, centerLogo / 55f));
                border.setColor(Color.argb(70, 0, 0, 0));
                qc.drawRoundRect(plate, 16f, 16f, border);

                qc.drawBitmap(scaledCenterLogo, cx, cy, null);
                qrBmp = outQr;
            }

            float byY = bottomBandBottom - 1f - byFm.descent;
            int qrY = (int) (byY - labelH - gapQrToLabel - qrSize);
            qrY = Math.max((int) (rowTop + 1), qrY);

            if (qrBmp != null) {
                Paint qrBack = new Paint(Paint.ANTI_ALIAS_FLAG);
                qrBack.setColor(Color.WHITE);
                int platePad = clamp(w / 650, 1, 3);
                RectF back = new RectF(qrX - platePad, qrY - platePad, qrX + qrSize + platePad, qrY + qrSize + platePad);
                canvas.drawRoundRect(back, 14f, 14f, qrBack);

                canvas.drawBitmap(qrBmp, qrX, qrY, null);

                float tW = byPaint.measureText(byLabel);
                float textX = qrX + (qrSize / 2f) - (tW / 2f);
                drawSmallItalicText(canvas, byLabel, textX, byY, qrSize, byTextSize);
            }

            // =========================
            // Draw LEFT logo (same height as QR)
            // =========================
            if (companyLogo != null) {
                Bitmap scaledLogo = Bitmap.createScaledBitmap(companyLogo, logoSize, logoSize, true);
                int logoY = (int) (bottomBandBottom - logoSize);
                canvas.drawBitmap(scaledLogo, logoX, logoY, null);
            }

            // =========================
            // Draw TEXT (bottom aligned to row)
            // =========================
            float textTop = bottomBandBottom - textBlockH;
            textTop = Math.max(textTop, rowTop);
            float y = textTop - fmTitle.ascent;

            drawTextStrokeNoShadow(canvas, line1, leftTextX, y, titleSize, maxTextWidth);
            y += titleStep + bodyGap;

            drawTextStrokeNoShadow(canvas, ellipsizeText(line2, bodySize, maxTextWidth), leftTextX, y, bodySize, maxTextWidth);
            y += bodyStep + smallGap;

            if (latLngOneLine) {
                drawTextStrokeNoShadow(canvas, oneLineLatLng, leftTextX, y, smallSize, maxTextWidth);
                y += smallStep + smallGap;
            } else {
                drawTextStrokeNoShadow(canvas, latLine, leftTextX, y, smallSize, maxTextWidth);
                y += smallStep + smallGap;
                drawTextStrokeNoShadow(canvas, lngLine + "  " + elevLine, leftTextX, y, smallSize, maxTextWidth);
                y += smallStep + smallGap;
            }

            drawTextStrokeNoShadow(canvas, line4, leftTextX, y, smallSize, maxTextWidth);
            y += smallStep + (smallGap * 0.30f);

            drawWrappedTextStrokeNoShadow(canvas, line5, leftTextX, y, smallSize, maxTextWidth, (smallStep + smallGap), 2);

            // Save JPEG
            FileOutputStream out = new FileOutputStream(file);
            mutable.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
            out.close();

            // Restore EXIF + GPS + SIG
            restoreExifAndWriteGpsAndSig(
                    file,
                    snap,
                    captureLocForExif,
                    signature,
                    uuid,
                    capturedAt,
                    project,
                    capturedBy,
                    modeToken
            );

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class ExifSnapshot {
        final Map<String, String> attrs = new HashMap<>();

        static ExifSnapshot read(File file) throws IOException {
            ExifSnapshot s = new ExifSnapshot();
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());

            for (String tag : COMMON_PRESERVE_TAGS) {
                String v = exif.getAttribute(tag);
                if (v != null) s.attrs.put(tag, v);
            }
            return s;
        }
    }

    private static final String[] COMMON_PRESERVE_TAGS = new String[] {
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,

            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,

            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,

            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,

            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_SHARPNESS,

            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_IMAGE_DESCRIPTION
    };

    private void restoreExifAndWriteGpsAndSig(File file,
                                              ExifSnapshot snap,
                                              Location loc,
                                              String sig,
                                              String uuid,
                                              String capturedAt,
                                              String project,
                                              String capturedBy,
                                              String modeToken) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());

            if (snap != null && snap.attrs != null) {
                for (Map.Entry<String, String> e : snap.attrs.entrySet()) {
                    String tag = e.getKey();
                    String val = e.getValue();
                    if (val == null) continue;

                    if (isGpsTag(tag)) continue;
                    if (ExifInterface.TAG_USER_COMMENT.equals(tag)) continue;

                    exif.setAttribute(tag, val);
                }
            }

            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,
                    safe(project, "PROJECT") + " - GeoKlik");
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Philmech GeoKlik");

            if (loc != null) {
                exif.setGpsInfo(loc);
                exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD,
                        safe(modeToken, "GPS_ONLY"));
            }

            if (capturedAt != null && !capturedAt.trim().isEmpty()) {
                String dt = capturedAt.replace('-', ':');
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt);
                exif.setAttribute(ExifInterface.TAG_DATETIME, dt);
            }

            String comment =
                    "PHILMECH_GEOKLIK" +
                            "\nUUID=" + safe(uuid, "--") +
                            "\nSIG=" + safe(sig, "--") +
                            "\nAT=" + safe(capturedAt, "--") +
                            "\nMODE=" + safe(modeToken, "GPS_ONLY") +
                            "\nELEV=" + getElevationForExifComment(loc) +
                            "\nSATS=" + gnssUsedInFix + "/" + gnssTotal;

            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment);
            exif.setAttribute(ExifInterface.TAG_ARTIST, safe(capturedBy, "Unknown"));
            exif.saveAttributes();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private String getElevationForExifComment(Location loc) {
        try {
            if (loc != null && loc.hasAltitude()) {
                return String.format(Locale.US, "%.1f m", loc.getAltitude());
            }
        } catch (Exception ignored) {}
        return "--";
    }

    private boolean isGpsTag(String tag) {
        if (tag == null) return false;
        return tag.startsWith("GPS")
                || ExifInterface.TAG_GPS_LATITUDE.equals(tag)
                || ExifInterface.TAG_GPS_LONGITUDE.equals(tag)
                || ExifInterface.TAG_GPS_LATITUDE_REF.equals(tag)
                || ExifInterface.TAG_GPS_LONGITUDE_REF.equals(tag)
                || ExifInterface.TAG_GPS_ALTITUDE.equals(tag)
                || ExifInterface.TAG_GPS_ALTITUDE_REF.equals(tag)
                || ExifInterface.TAG_GPS_TIMESTAMP.equals(tag)
                || ExifInterface.TAG_GPS_DATESTAMP.equals(tag)
                || ExifInterface.TAG_GPS_PROCESSING_METHOD.equals(tag);
    }

    private String buildCanonicalForSig(String uuid,
                                        String project,
                                        String site,
                                        String description,
                                        Double lat,
                                        Double lng,
                                        Double acc,
                                        String address,
                                        String capturedAt) {

        String latStr = (lat == null) ? "--" : String.format(Locale.US, "%.6f", lat);
        String lngStr = (lng == null) ? "--" : String.format(Locale.US, "%.6f", lng);
        String accStr = (acc == null) ? "--" : String.format(Locale.US, "±%.1f m", acc);

        return uuid + "|" +
                project + "|" +
                site + "|" +
                description + "|" +
                latStr + "|" +
                lngStr + "|" +
                accStr + "|" +
                address + "|" +
                capturedAt;
    }

    private String buildReadableQr(String uuid,
                                   String project,
                                   String site,
                                   String description,
                                   Double lat,
                                   Double lng,
                                   Double acc,
                                   String address,
                                   String capturedAt) {

        String latStr = (lat == null) ? "--" : String.format(Locale.US, "%.6f", lat);
        String lngStr = (lng == null) ? "--" : String.format(Locale.US, "%.6f", lng);
        String accStr = (acc == null) ? "--" : String.format(Locale.US, "±%.1f m", acc);

        String canonical =
                uuid + "|" +
                        project + "|" +
                        site + "|" +
                        description + "|" +
                        latStr + "|" +
                        lngStr + "|" +
                        accStr + "|" +
                        address + "|" +
                        capturedAt;

        String signature = "";
        try {
            signature = hmacSha256Base64Url(QR_HMAC_SECRET, canonical);
        } catch (Exception ignored) {}

        return "PHILMECH GEOKLIK\n" +
                "--------------------------\n" +
                "Project : " + project + "\n" +
                "Site    : " + site + "\n" +
                "Desc    : " + description + "\n" +
                "Location: " + latStr + ", " + lngStr + "\n" +
                "Accuracy: " + accStr + "\n" +
                "Address : " + address + "\n" +
                "Date    : " + capturedAt + "\n" +
                "UUID    : " + uuid + "\n\n" +
                "SIG     : " + signature;
    }

    private String hmacSha256Base64Url(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(key);
        byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private Bitmap createQrBitmap(String content, int w, int h) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0); // let us control it

            hints.put(EncodeHintType.ERROR_CORRECTION,
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H);

            BitMatrix matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, w, h, hints);

            // ✅ add a small quiet zone ourselves (pad in modules-ish; try 6)
            BitMatrix cropped = cropToContent(matrix, 6);

            Bitmap bmp = bitMatrixToBitmap(cropped);
            return Bitmap.createScaledBitmap(bmp, w, h, true);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Bitmap createBarcodeBitmap(String content, int w, int h) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1); // bawasan, sobrang laki ang 6

            BitMatrix matrix = new MultiFormatWriter()
                    .encode(content, BarcodeFormat.CODE_128, w, h, hints);

            // ✅ crop sa actual black bars para hindi full white block
            BitMatrix cropped = cropToContent(matrix, 6); // 6px padding around bars
            Bitmap bmp = bitMatrixToBitmap(cropped);

            // ✅ optional: scale back to desired size
            return Bitmap.createScaledBitmap(bmp, w, h, true);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private BitMatrix cropToContent(BitMatrix matrix, int pad) {
        int[] rect = matrix.getEnclosingRectangle(); // [left, top, width, height]
        if (rect == null) return matrix;

        int left = Math.max(0, rect[0] - pad);
        int top  = Math.max(0, rect[1] - pad);
        int right = Math.min(matrix.getWidth(), rect[0] + rect[2] + pad);
        int bottom = Math.min(matrix.getHeight(), rect[1] + rect[3] + pad);

        int newW = right - left;
        int newH = bottom - top;

        BitMatrix out = new BitMatrix(newW, newH);
        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                if (matrix.get(left + x, top + y)) out.set(x, y);
            }
        }
        return out;
    }

    // ✅ FAST: no per-pixel setPixel loop
    private Bitmap bitMatrixToBitmap(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();

        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, width, 0, 0, width, height);
        return bmp;
    }

    private Bitmap overlayCenterLogoOnQr(Bitmap qr, Bitmap logo) {
        if (qr == null || logo == null) return qr;

        Bitmap out = qr.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);

        int qrW = out.getWidth();
        int qrH = out.getHeight();

        int logoSize = (int) (Math.min(qrW, qrH) * 0.15f);
        logoSize = Math.max(70, logoSize);
        logoSize = Math.min(logoSize, (int) (Math.min(qrW, qrH) * 0.24f));

        Bitmap scaledLogo = Bitmap.createScaledBitmap(logo, logoSize, logoSize, true);

        int cx = (qrW - logoSize) / 2;
        int cy = (qrH - logoSize) / 2;

        float platePad = Math.max(10f, logoSize * 0.12f);
        RectF plate = new RectF(
                cx - platePad,
                cy - platePad,
                cx + logoSize + platePad,
                cy + logoSize + platePad
        );

        Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        platePaint.setColor(Color.WHITE);
        c.drawRoundRect(plate, 18f, 18f, platePaint);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(2f, logoSize / 45f));
        border.setColor(Color.argb(90, 0, 0, 0));
        c.drawRoundRect(plate, 18f, 18f, border);

        c.drawBitmap(scaledLogo, cx, cy, null);
        return out;
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void drawTextStrokeNoShadow(Canvas canvas, String text, float x, float y, float textSize, float maxWidth) {
        if (text == null) return;

        String draw = ellipsizeText(text, textSize, maxWidth);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setTextSize(textSize);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(3f, textSize / 12f));
        stroke.setColor(Color.BLACK);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setTextSize(textSize);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.WHITE);

        canvas.drawText(draw, x, y, stroke);
        canvas.drawText(draw, x, y, fill);
    }

    private void drawWrappedTextStrokeNoShadow(Canvas canvas, String text, float x, float y,
                                               float textSize, float maxWidth, float lineHeight, int maxLines) {
        if (text == null) return;

        Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
        measure.setTextSize(textSize);

        if (measure.measureText(text) <= maxWidth) {
            drawTextStrokeNoShadow(canvas, text, x, y, textSize, maxWidth);
            return;
        }

        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        float yy = y;
        int lines = 0;

        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            String test = (line.length() == 0) ? w : line + " " + w;

            if (measure.measureText(test) > maxWidth) {
                drawTextStrokeNoShadow(canvas, line.toString(), x, yy, textSize, maxWidth);
                yy += lineHeight;
                lines++;

                if (lines >= maxLines - 1) {
                    StringBuilder remaining = new StringBuilder(w);
                    for (int j = i + 1; j < words.length; j++) remaining.append(" ").append(words[j]);
                    drawTextStrokeNoShadow(canvas, ellipsizeText(remaining.toString(), textSize, maxWidth), x, yy, textSize, maxWidth);
                    return;
                }

                line = new StringBuilder(w);
            } else {
                line = new StringBuilder(test);
            }
        }

        drawTextStrokeNoShadow(canvas, line.toString(), x, yy, textSize, maxWidth);
    }

    private String ellipsizeText(String text, float textSize, float maxWidth) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(textSize);

        if (p.measureText(text) <= maxWidth) return text;

        String ell = "...";
        float ellW = p.measureText(ell);

        int end = text.length();
        while (end > 0 && p.measureText(text, 0, end) + ellW > maxWidth) {
            end--;
        }
        return (end <= 0) ? ell : text.substring(0, end) + ell;
    }

    private void drawSmallItalicText(Canvas canvas, String text, float x, float y, float maxWidth, float textSize) {
        if (text == null) return;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(textSize);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setTextSize(textSize);
        stroke.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, textSize / 10f));
        stroke.setColor(Color.BLACK);

        String draw = ellipsizeText(text, textSize, maxWidth);

        canvas.drawText(draw, x, y, stroke);
        canvas.drawText(draw, x, y, p);
    }

    private static String safe(String s, String def) {
        if (s == null || s.trim().isEmpty()) return def;
        return s.trim();
    }

    private static String safeNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String sanitizeFolder(String input) {
        if (input == null) return "GENERAL";
        String s = input.trim();
        if (s.isEmpty()) return "GENERAL";
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        s = s.replaceAll("\\s+", "_");
        if (s.length() > 50) s = s.substring(0, 50);
        return s;
    }
}