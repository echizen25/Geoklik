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
import ph.gov.geocamera.core.utils.OfflineLocationLookup;
import ph.gov.geocamera.core.utils.ProvinceLookup;
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
    private android.widget.TextView tvGpsWarning, tvGpsMeta;
    private View viewAccuracyIndicator;
    private android.widget.TextView tvAccuracyBadge;
    private android.widget.TextView tvCaptureStatus;

    private View viewShutterFlash;

    private ImageCapture imageCapture;
    private Executor mainExecutor;
    private CameraStateManager cameraStateManager;
    private CameraGestureController cameraGestureController;

    private LocationManager locationManager;
    private FusedLocationProviderClient fusedLocationClient;
    private SettingsClient settingsClient;
    private LocationCallback fusedCallback;
    private LocationRequest fusedRequest;

    private volatile Location gpsLastLocation = null;
    private volatile Location gpsBestLocation = null;
    private volatile Location fusedLastLocation = null;
    private Location lastLocation;

    private volatile int gnssUsedInFix = 0;
    private volatile int gnssTotal = 0;
    private volatile long lastGnssAt = 0L;

    private long firstGoodFixAt = 0L;
    private static final long REQUIRED_GOOD_MS = 1800;
    private static final long GPS_FRESH_MS = 8_000;

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

    private static final float OUTDOOR_GOOD_ACC = 8f;
    private static final float OUTDOOR_OK_ACC   = 18f;
    private static final float INDOOR_MAX_ACC   = 60f;
    private static final float GPS_WARN_ACC      = 20f;
    private static final float GPS_BLOCK_ACC     = 50f;
    private static final double DUPLICATE_RADIUS_METERS = 5.0;

    private static final int REQUIRED_STABLE_FIX_COUNT = 2;
    private int stableFixCount = 0;
    private Preview previewUseCase;
    private volatile boolean isCapturing = false;

    private int lastRotation = Surface.ROTATION_0;
    private boolean allowIndoorFallback = false;
    private static final int MIN_USED_SATS = 4;

    private ActivityResultLauncher<Intent> setSiteLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ProjectRepository projectRepo;
    private long lastGeocodeAt = 0L;
    private double lastGeoLat = 0.0;
    private double lastGeoLng = 0.0;
    private String lastAddressText = "Province, City/Municipality";

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

        userRepo = new UserRepository(this);
        groupRepo = new GroupRepository(this);
        siteRepo = new SiteRepository(this);
        imageRepo = new ImageMetaRepository(this);
        projectRepo = new ProjectRepository(this);

        cameraPrefs = new CameraPrefs(this);
        allowIndoorFallback = cameraPrefs.isIndoorAssistEnabled();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        settingsClient = LocationServices.getSettingsClient(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        previewView = findViewById(R.id.previewView);
        cameraGestureController = new CameraGestureController(this, previewView);
        btnCapture = findViewById(R.id.btnCapture);
        btnCamSettings = findViewById(R.id.btnCamSettings);
        tvCaptureStatus = findViewById(R.id.tvCaptureStatus);

        tvLatLng = findViewById(R.id.tvLatLng);
        tvAccuracy = findViewById(R.id.tvAccuracy);
        tvProject = findViewById(R.id.tvProject);
        tvDesc = findViewById(R.id.tvDesc);
        tvWatermarkLatLng = findViewById(R.id.tvWatermarkLatLng);
        tvDate = findViewById(R.id.tvDate);
        tvAddress = findViewById(R.id.tvAddress);
        tvGpsWarning = findViewById(R.id.tvGpsWarning);
        tvGpsMeta = findViewById(R.id.tvGpsMeta);
        viewAccuracyIndicator = findViewById(R.id.viewAccuracyIndicator);
        tvAccuracyBadge = findViewById(R.id.tvAccuracyBadge);
        viewShutterFlash = findViewById(R.id.viewShutterFlash);

        cameraStateManager = new CameraStateManager(btnCapture, tvCaptureStatus);
        setupOrientationListener();

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
                    isPickingSite = false;
                    if (res.getResultCode() != RESULT_OK || res.getData() == null) {
                        updateCaptureAvailability();
                        return;
                    }

                    Intent data = res.getData();
                    String siteId = data.getStringExtra(SetSiteActivity.EXTRA_SITE_ID);
                    boolean uncat = data.getBooleanExtra(SetSiteActivity.EXTRA_UNCATEGORIZED, false);
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

        loadSiteFromPrefs();
        activeDescription = safeNull(cameraPrefs.getDescription());
        updateOverlayTexts();
        updateCaptureAvailability();
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
                                        updateGpsPresentation(lastLocation);
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        } else {
                            allowIndoorFallback = false;
                            cameraPrefs.saveIndoorAssistEnabled(false);
                            Toast.makeText(this, "Indoor Assist OFF (GPS-Only)", Toast.LENGTH_SHORT).show();
                            updateCaptureAvailability();
                            updateGpsPresentation(lastLocation);
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
            discardCapturedPhoto(file, "Photo discarded.");
            return;
        }

        final Double lat = captureLoc.getLatitude();
        final Double lng = captureLoc.getLongitude();
        final Double accD = (double) captureLoc.getAccuracy();
        setCaptureState(CameraStateManager.State.PROCESSING);

        new Thread(() -> {
            boolean metadataInserted = false;
            try {
                String addressText = getProvinceCityBlocking(lat, lng);
                String modeToken = allowIndoorFallback ? "INDOOR_ASSIST" : "GPS_ONLY";

                String androidIdFromDb = getAndroidIdFromTblUsers();
                String capturedBy = getFullNameFromTblUsers();

                String watermarkSiteLabel = siteId;
                if (siteId != null && !"UNCAT".equalsIgnoreCase(siteId)) {
                    String coda = projectRepo.getProjectCodaById(siteId);
                    if (coda != null && !coda.trim().isEmpty()) watermarkSiteLabel = coda.trim();
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

                if (file == null || !file.exists() || file.length() <= 0) {
                    throw new IOException("Processed image is unavailable.");
                }

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
                metadataInserted = true;

                try { SyncScheduler.enqueueUploadNow(GeoCameraActivity.this); } catch (Exception ignored) {}
                try {
                    int count = imageRepo.countBySite(siteId);
                    siteRepo.updateImageCount(siteId, count);
                } catch (Exception ignored) {}

                finishCaptureSuccess();
            } catch (Exception e) {
                e.printStackTrace();
                if (!metadataInserted) deleteQuietly(file);
                finishCaptureError("Photo processing failed. Please capture the photo again.");
            }
        }).start();
    }

    private void enforceSiteSelectionFirstOnly() {
        if (isPickingSite) return;
        if (!cameraPrefs.hasSelection()) {
            isPickingSite = true;
            setSiteLauncher.launch(new Intent(this, SetSiteActivity.class));
        }
    }

    private void capturePhotoThenAskDesc() {
        if (isCapturing || (cameraStateManager != null && cameraStateManager.isBusy())) return;

        if (!cameraPrefs.hasSelection()) {
            setCaptureState(CameraStateManager.State.SELECT_SITE);
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
            updateCaptureAvailability();
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
        } else if (!isFreshEither(captureLoc) || captureLoc.getAccuracy() > INDOOR_MAX_ACC) {
            Toast.makeText(this, "Location too weak (±" + Math.round(captureLoc.getAccuracy()) + "m).", Toast.LENGTH_LONG).show();
            return;
        }

        isCapturing = true;
        setCaptureState(CameraStateManager.State.CHECKING_DUPLICATE);

        final String project = safe(userRepo.getProject(), "PROJECT");
        final String userId = safe(userRepo.getUserId(), "UNKNOWN");
        final String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date());
        final String motherFolder = project + "_" + year;
        final String siteId = (activeSiteId == null) ? "UNCAT" : activeSiteId;
        final String sessionDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        if (imageRepo.hasNearbyPhoto(siteId, captureLoc.getLatitude(), captureLoc.getLongitude(), DUPLICATE_RADIUS_METERS)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Possible Duplicate Photo")
                    .setMessage("A photo for this site was already captured within "
                            + Math.round(DUPLICATE_RADIUS_METERS)
                            + " meters of your current location. Continue anyway?")
                    .setNegativeButton("Cancel", (d, w) -> resetCaptureLifecycle())
                    .setPositiveButton("Continue", (d, w) -> continueCaptureAfterDuplicateCheck(
                            captureLoc, project, userId, motherFolder, siteId, sessionDate))
                    .setOnCancelListener(d -> resetCaptureLifecycle())
                    .show();
            return;
        }

        continueCaptureAfterDuplicateCheck(captureLoc, project, userId, motherFolder, siteId, sessionDate);
    }

    private void continueCaptureAfterDuplicateCheck(
            Location captureLoc,
            String project,
            String userId,
            String motherFolder,
            String siteId,
            String sessionDate
    ) {
        setCaptureState(CameraStateManager.State.CAPTURING);
        playCaptureAnimation();

        final String groupId = groupRepo.getOrCreateGroup(motherFolder, siteId, sessionDate, null);
        final File base = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (base == null) {
            finishCaptureError("Storage is unavailable.");
            return;
        }

        String folderRel = groupRepo.getFolderNameByGroupId(groupId);
        if (folderRel == null || folderRel.trim().isEmpty()) {
            folderRel = motherFolder + "/" + siteId + "/" + sessionDate;
        }

        final File folder = new File(base, folderRel);
        if (!folder.exists() && !folder.mkdirs()) {
            finishCaptureError("Unable to create the photo folder.");
            return;
        }

        try {
            File noMedia = new File(folder, ".nomedia");
            if (!noMedia.exists()) noMedia.createNewFile();
        } catch (Exception ignored) {}

        final String uuid = UUID.randomUUID().toString();
        final File file = new File(folder, "IMG_" + uuid + ".jpg");

        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(file).build();
        imageCapture.takePicture(options, mainExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                setCaptureState(CameraStateManager.State.ADD_DESCRIPTION);
                promptPhotoDescription(file, desc -> postProcessAndSaveToDb(
                        file, uuid, groupId, siteId, project, userId, captureLoc, desc));
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                deleteQuietly(file);
                finishCaptureError("Capture failed. Please try again.");
            }
        });
    }

    private interface DescCallback {
        void onDesc(String desc);
    }

    private void promptPhotoDescription(@NonNull File capturedFile, @NonNull DescCallback onDone) {
        TextInputLayout til = new TextInputLayout(this);
        til.setHint("Photo Description (e.g. Front view, Engine, Serial no.)");
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

        TextInputEditText et = new TextInputEditText(this);
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        til.addView(et);

        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Photo Description")
                .setMessage("Describe this photo before saving it to GeoKlik.")
                .setView(til)
                .setPositiveButton("Save Photo", null)
                .setNegativeButton("Discard Photo", null)
                .setCancelable(false)
                .show();

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String desc = et.getText() == null ? "" : et.getText().toString().trim();
                    if (desc.isEmpty()) {
                        til.setError("Description is required.");
                        return;
                    }
                    til.setError(null);
                    dialog.dismiss();
                    onDone.onDesc(desc);
                });

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> {
                    dialog.dismiss();
                    discardCapturedPhoto(capturedFile, "Photo discarded.");
                });
    }

    private void discardCapturedPhoto(File file, String message) {
        deleteQuietly(file);
        runOnUiThread(() -> {
            if (message != null && !message.trim().isEmpty()) {
                Toast.makeText(GeoCameraActivity.this, message, Toast.LENGTH_SHORT).show();
            }
            resetCaptureLifecycle();
        });
    }

    private void finishCaptureSuccess() {
        runOnUiThread(() -> {
            setCaptureState(CameraStateManager.State.SAVED);
            Toast.makeText(GeoCameraActivity.this,
                    "Photo saved. Queued for synchronization.", Toast.LENGTH_SHORT).show();
            btnCapture.postDelayed(this::resetCaptureLifecycle, 900L);
        });
    }

    private void finishCaptureError(String message) {
        runOnUiThread(() -> {
            setCaptureState(CameraStateManager.State.ERROR);
            Toast.makeText(GeoCameraActivity.this,
                    message == null ? "Capture failed." : message, Toast.LENGTH_LONG).show();
            btnCapture.postDelayed(this::resetCaptureLifecycle, 1200L);
        });
    }

    private void resetCaptureLifecycle() {
        isCapturing = false;
        updateCaptureAvailability();
    }

    private void deleteQuietly(File file) {
        if (file == null) return;
        try { if (file.exists()) file.delete(); } catch (Exception ignored) {}
    }

    private void setCaptureState(CameraStateManager.State state) {
        if (cameraStateManager != null) cameraStateManager.apply(state);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                int rotation = (previewView != null && previewView.getDisplay() != null)
                        ? previewView.getDisplay().getRotation() : Surface.ROTATION_0;
                lastRotation = rotation;

                previewUseCase = new Preview.Builder().setTargetRotation(rotation).build();
                previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setTargetRotation(rotation)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(92)
                        .build();

                provider.unbindAll();
                androidx.camera.core.Camera boundCamera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        imageCapture);
                if (cameraGestureController != null) {
                    cameraGestureController.attachCamera(boundCamera);
                }
            } catch (Exception e) {
                e.printStackTrace();
                finishCaptureError("Camera failed to start.");
            }
        }, mainExecutor);
    }

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            int used = 0;
            int total = status.getSatelliteCount();
            for (int i = 0; i < total; i++) if (status.usedInFix(i)) used++;
            gnssUsedInFix = used;
            gnssTotal = total;
            lastGnssAt = System.currentTimeMillis();
            runOnUiThread(() -> {
                updateGpsPresentation(lastLocation);
                updateCaptureAvailability();
            });
        }
    };

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location loc) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && loc.isMock()) return;
            gpsLastLocation = loc;
            pruneBestGpsIfStale();
            if (gpsBestLocation == null || loc.getAccuracy() < gpsBestLocation.getAccuracy()) {
                gpsBestLocation = loc;
            }
            lastLocation = chooseDisplayLocation();
            runOnUiThread(() -> {
                if (lastLocation != null) updateOverlay(lastLocation);
                updateCaptureAvailability();
            });
        }

        @Override @Deprecated
        public void onStatusChanged(String provider, int status, Bundle extras) {}

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
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                showLocationUnavailable();
                updateCaptureAvailability();
                Toast.makeText(this, "Please enable GPS.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                return;
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return;

            try {
                locationManager.registerGnssStatusCallback(gnssCallback,
                        new android.os.Handler(Looper.getMainLooper()));
            } catch (Exception ignored) {}

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    1000L, 0f, gpsListener, Looper.getMainLooper());

            Location lk = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lk != null) {
                gpsLastLocation = lk;
                pruneBestGpsIfStale();
                if (gpsBestLocation == null || lk.getAccuracy() < gpsBestLocation.getAccuracy()) {
                    gpsBestLocation = lk;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            showLocationUnavailable();
            updateCaptureAvailability();
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return;
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
        if (!isPickingSite) enforceSiteSelectionFirstOnly();
        if (orientationListener != null && orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
        if (hasFineLocation()) startLiveLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (orientationListener != null) orientationListener.disable();
        stopLiveLocation();
    }

    private Location chooseDisplayLocation() {
        Location gps = gpsLastLocation;
        if (gps != null && isFresh(gps)) return gps;
        if (allowIndoorFallback) {
            Location f = fusedLastLocation;
            if (f != null && isFreshFused(f)) return f;
        }
        return gps != null ? gps : fusedLastLocation;
    }

    private void updateOverlay(@NonNull Location loc) {
        tvLatLng.setText(String.format(Locale.US,
                "Lat: %.6f  Lng: %.6f", loc.getLatitude(), loc.getLongitude()));
        tvAccuracy.setText(String.format(Locale.US, "Accuracy: ±%.1f m", loc.getAccuracy()));
        updateGpsPresentation(loc);
        updateOverlayTexts();
    }

    private void updateGpsPresentation(Location loc) {
        updateGpsAccuracyWarning(loc);
        updateAccuracyBadge(loc);
        updateGpsMeta(loc);
    }

    private void updateGpsMeta(Location location) {
        if (tvGpsMeta == null) return;
        if (location == null || !location.hasAccuracy()) {
            tvGpsMeta.setText("SATS --/-- • Need ≤" + Math.round(OUTDOOR_OK_ACC) + " m / ≥" + MIN_USED_SATS);
            return;
        }

        if (allowIndoorFallback && (!isFresh(gpsLastLocation) || gnssUsedInFix < MIN_USED_SATS)) {
            tvGpsMeta.setText(String.format(Locale.US,
                    "±%.0f m • INDOOR ASSIST • Max ≤%.0f m",
                    location.getAccuracy(), INDOOR_MAX_ACC));
            return;
        }

        boolean gnssFresh = (System.currentTimeMillis() - lastGnssAt) < 10_000;
        String sats = gnssFresh ? gnssUsedInFix + "/" + gnssTotal : "--/--";
        float acc = gpsLastLocation != null ? gpsLastLocation.getAccuracy() : location.getAccuracy();
        tvGpsMeta.setText(String.format(Locale.US,
                "±%.0f m • SATS %s • Need ≤%.0f m / ≥%d",
                acc, sats, OUTDOOR_OK_ACC, MIN_USED_SATS));
    }

    private void updateGpsAccuracyWarning(Location loc) {
        if (tvGpsWarning == null) return;
        tvGpsWarning.setVisibility(View.VISIBLE);

        if (loc == null || !loc.hasAccuracy()) {
            tvGpsWarning.setText("Waiting for an accurate GPS fix…");
            tvGpsWarning.setTextColor(Color.WHITE);
            return;
        }

        if (allowIndoorFallback && (!isFresh(gpsLastLocation) || gnssUsedInFix < MIN_USED_SATS)) {
            tvGpsWarning.setText("Indoor Assist active • verify the location before capture.");
            tvGpsWarning.setTextColor(Color.parseColor("#B2EBF2"));
            return;
        }

        float acc = loc.getAccuracy();
        if (acc <= OUTDOOR_GOOD_ACC && gnssUsedInFix >= MIN_USED_SATS) {
            tvGpsWarning.setText("Ready for capture.");
            tvGpsWarning.setTextColor(Color.parseColor("#B9F6CA"));
        } else if (acc <= OUTDOOR_OK_ACC && gnssUsedInFix >= MIN_USED_SATS) {
            tvGpsWarning.setText("Hold steady while GPS stabilizes.");
            tvGpsWarning.setTextColor(Color.parseColor("#FFF59D"));
        } else if (gnssUsedInFix < MIN_USED_SATS) {
            tvGpsWarning.setText("Waiting for satellite fix • move to open sky.");
            tvGpsWarning.setTextColor(Color.parseColor("#FFF59D"));
        } else {
            tvGpsWarning.setText("Move to open sky and wait for a stronger GPS fix.");
            tvGpsWarning.setTextColor(Color.parseColor("#FFCDD2"));
        }
    }

    private void showLocationUnavailable() {
        tvLatLng.setText("Lat: --, Lng: --");
        tvAccuracy.setText("Accuracy: -- m");
        setBadge(Color.GRAY, "WAIT GPS");
        updateGpsPresentation(null);
        updateOverlayTexts();
    }

    private void updateAccuracyBadge(Location location) {
        if (location == null) {
            setBadge(Color.GRAY, "WAIT GPS");
            return;
        }

        boolean gpsFresh = isFresh(gpsLastLocation);
        if (gpsFresh && gpsLastLocation != null) {
            float gpsAcc = gpsLastLocation.getAccuracy();
            if (gnssUsedInFix < MIN_USED_SATS) {
                setBadge(Color.rgb(255, 140, 0), "NO FIX");
                return;
            }
            if (gpsAcc <= OUTDOOR_OK_ACC) {
                boolean stable = firstGoodFixAt > 0L
                        && (System.currentTimeMillis() - firstGoodFixAt) >= REQUIRED_GOOD_MS;
                setBadge(Color.GREEN, stable ? "GPS LOCK" : "STABILIZING");
                return;
            }
            if (allowIndoorFallback) {
                setBadge(Color.CYAN, "INDOOR ASSIST");
            } else {
                setBadge(Color.RED, "GPS WEAK");
            }
            return;
        }

        if (allowIndoorFallback && fusedLastLocation != null && isFreshFused(fusedLastLocation)) {
            setBadge(Color.CYAN, "INDOOR ASSIST");
            return;
        }
        setBadge(Color.GRAY, "STALE");
    }

    private void setBadge(int color, String text) {
        if (viewAccuracyIndicator != null) {
            viewAccuracyIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        }
        if (tvAccuracyBadge != null) tvAccuracyBadge.setText(text);
    }

    private void pruneBestGpsIfStale() {
        if (gpsBestLocation != null && !isFresh(gpsBestLocation)) gpsBestLocation = null;
    }

    private void updateCaptureAvailability() {
        pruneBestGpsIfStale();
        if (isCapturing || (cameraStateManager != null && cameraStateManager.isBusy())) return;

        if (!cameraPrefs.hasSelection()) {
            firstGoodFixAt = 0L;
            stableFixCount = 0;
            setCaptureState(CameraStateManager.State.SELECT_SITE);
            return;
        }

        if (!allowIndoorFallback) {
            Location gps = gpsLastLocation;
            if (gps == null || !isFresh(gps)) {
                firstGoodFixAt = 0L;
                setCaptureState(CameraStateManager.State.WAITING_FOR_GPS);
                updateGpsPresentation(gps);
                return;
            }

            if (gnssUsedInFix < MIN_USED_SATS) {
                firstGoodFixAt = 0L;
                setCaptureState(CameraStateManager.State.WAITING_FOR_GPS);
                updateGpsPresentation(gps);
                return;
            }

            float acc = gps.getAccuracy();
            if (acc <= OUTDOOR_OK_ACC) {
                if (firstGoodFixAt == 0L) firstGoodFixAt = System.currentTimeMillis();
                boolean allow = (System.currentTimeMillis() - firstGoodFixAt) >= REQUIRED_GOOD_MS;
                setCaptureState(allow ? CameraStateManager.State.READY
                        : CameraStateManager.State.STABILIZING);
            } else {
                firstGoodFixAt = 0L;
                setCaptureState(CameraStateManager.State.GPS_WEAK);
            }
            updateGpsPresentation(gps);
            return;
        }

        Location capture = chooseCaptureLocation();
        if (capture == null || !isFreshEither(capture)) {
            setCaptureState(CameraStateManager.State.WAITING_FOR_GPS);
            updateGpsPresentation(capture);
            return;
        }

        if (capture.getAccuracy() <= INDOOR_MAX_ACC) {
            setCaptureState(CameraStateManager.State.READY);
        } else {
            setCaptureState(CameraStateManager.State.GPS_WEAK);
        }
        updateGpsPresentation(capture);
    }

    private void disableCapture() {
        if (btnCapture != null) {
            btnCapture.setEnabled(false);
            btnCapture.setAlpha(0.35f);
        }
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

        if (tvProject != null) tvProject.setText(project + " | " + siteLabel);

        String desc = safeNull(cameraPrefs.getDescription());
        if (tvDesc != null) tvDesc.setText(desc == null ? "N/A" : desc);

        if (tvDate != null) {
            String dateStr = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(new Date());
            tvDate.setText(dateStr);
        }

        Location loc = lastLocation;
        if (loc != null) {
            if (tvWatermarkLatLng != null) {
                String elevText = loc.hasAltitude()
                        ? String.format(Locale.US, "%.1f m", loc.getAltitude()) : "--";
                tvWatermarkLatLng.setText(String.format(Locale.US,
                        "Lat: %.6f | Lng: %.6f | Elev: %s",
                        loc.getLatitude(), loc.getLongitude(), elevText));
            }
            updateAddressFromLocation(loc);
        } else {
            if (tvWatermarkLatLng != null) tvWatermarkLatLng.setText("Lat: -- | Lng: --");
            if (tvAddress != null) tvAddress.setText("Province, City/Municipality");
        }
    }

    private String getSelectedSiteIdOrUncat() {
        return (activeSiteId == null || activeSiteId.trim().isEmpty()) ? "UNCAT" : activeSiteId.trim();
    }

    private String getSelectedProjectIdForUpload() {
        if (activeUncategorized || activeSiteId == null || activeSiteId.trim().isEmpty()) return "";
        String pid = siteRepo.getProjectIdBySiteId(activeSiteId);
        return pid == null ? "" : pid.trim();
    }

    private String getSelectedProjectLabelForDisplay() {
        if (activeUncategorized || activeSiteId == null || activeSiteId.trim().isEmpty()) return "UNCAT";
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

        if (!movedEnough && (now - lastGeocodeAt) < 6000) {
            tvAddress.setText(lastAddressText);
            return;
        }

        lastGeocodeAt = now;
        lastGeoLat = loc.getLatitude();
        lastGeoLng = loc.getLongitude();

        final String offline = tryOfflineProvinceCity(loc.getLatitude(), loc.getLongitude());
        final String offlineSafe = (offline != null && !offline.trim().isEmpty())
                ? offline.trim() : "Province";

        Geocoder geocoder = new Geocoder(this, Locale.getDefault());
        if (!Geocoder.isPresent()) {
            lastAddressText = offlineSafe;
            tvAddress.setText(lastAddressText);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1, addresses -> {
                String txt = formatProvinceCity(addresses);
                if (txt == null) txt = "";
                txt = txt.trim();
                boolean bad = txt.isEmpty()
                        || txt.equalsIgnoreCase("Province")
                        || txt.equalsIgnoreCase("Province, City")
                        || txt.equalsIgnoreCase("Province, City/Municipality");
                final String finalTxt = bad ? offlineSafe : txt;
                runOnUiThread(() -> {
                    lastAddressText = finalTxt;
                    if (tvAddress != null) tvAddress.setText(finalTxt);
                });
            });
            return;
        }

        new Thread(() -> {
            String txt = "";
            try {
                List<Address> addresses = geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                String t = formatProvinceCity(addresses);
                if (t != null) txt = t.trim();
            } catch (Exception ignored) {}

            boolean bad = txt.isEmpty()
                    || txt.equalsIgnoreCase("Province")
                    || txt.equalsIgnoreCase("Province, City")
                    || txt.equalsIgnoreCase("Province, City/Municipality");
            final String finalTxt = bad ? offlineSafe : txt;
            runOnUiThread(() -> {
                lastAddressText = finalTxt;
                if (tvAddress != null) tvAddress.setText(finalTxt);
            });
        }).start();
    }

    private String tryOfflineProvinceCity(double lat, double lng) {
        try {
            String value = OfflineLocationLookup.getDisplayName(this, lat, lng);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        } catch (Exception ignored) {}
        try {
            return ProvinceLookup.getNearestProvince(lat, lng);
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
        return province;
    }

    private String getProvinceCityBlocking(double latitude, double longitude) {
        try {
            if (Geocoder.isPresent()) {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> list = geocoder.getFromLocation(latitude, longitude, 1);
                String result = formatProvinceCity(list);
                if (result != null && !result.trim().isEmpty()) return result.trim();
            }
        } catch (Exception ignored) {}

        String offline = tryOfflineProvinceCity(latitude, longitude);
        if (offline != null && !offline.trim().isEmpty()) return offline.trim();
        return "Province";
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
        return ageMs >= 0 && ageMs < 12_000;
    }

    private boolean isFreshEither(Location l) {
        if (l == null) return false;
        String p = l.getProvider();
        if (p != null && p.equalsIgnoreCase(LocationManager.GPS_PROVIDER)) return isFresh(l);
        return isFreshFused(l);
    }

    private Location chooseCaptureLocation() {
        if (!allowIndoorFallback) {
            if (gpsLastLocation != null && isFresh(gpsLastLocation) && gnssUsedInFix >= MIN_USED_SATS) {
                if (gpsBestLocation != null && isFresh(gpsBestLocation)
                        && gpsBestLocation.getAccuracy() <= gpsLastLocation.getAccuracy()) {
                    return gpsBestLocation;
                }
                return gpsLastLocation;
            }
            return null;
        }

        if (gpsLastLocation != null && isFresh(gpsLastLocation)
                && gnssUsedInFix >= MIN_USED_SATS
                && gpsLastLocation.getAccuracy() <= OUTDOOR_OK_ACC) {
            if (gpsBestLocation != null && isFresh(gpsBestLocation)) return gpsBestLocation;
            return gpsLastLocation;
        }

        if (fusedLastLocation != null && isFreshFused(fusedLastLocation)
                && fusedLastLocation.getAccuracy() <= INDOOR_MAX_ACC) return fusedLastLocation;

        if (gpsLastLocation != null && isFresh(gpsLastLocation)
                && gpsLastLocation.getAccuracy() <= INDOOR_MAX_ACC) return gpsLastLocation;
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
            return id == null ? "" : id.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String getFullNameFromTblUsers() {
        Cursor c = null;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            c = db.rawQuery("SELECT fname, lname FROM tbl_users ORDER BY timestamp DESC LIMIT 1", null);
            if (c.moveToFirst()) {
                String fn = c.getString(0);
                String ln = c.getString(1);
                fn = fn == null ? "" : fn.trim();
                ln = ln == null ? "" : ln.trim();
                String full = (fn + " " + ln).trim();
                return full.isEmpty() ? "Unknown" : full;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "Unknown";
    }

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
            float bodyGap  = Math.max(3f, bodyStep * 0.10f);
            float smallGap = Math.max(2f, smallStep * 0.08f);

            int barcodeW = clamp((int) (w * 0.72f), 520, w - (pad * 2));
            int barcodeH = clamp(h / 26, 90, 170);

            String datePretty = new SimpleDateFormat("MMMM dd, yyyy", Locale.US).format(new Date());
            String timePretty = new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date());
            String capturedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

            String canonicalForSig = buildCanonicalForSig(
                    uuid, project, siteLabel, descriptionForQr, lat, lng, acc, provinceCity, capturedAt);
            String signature = "";
            try { signature = hmacSha256Base64Url(QR_HMAC_SECRET, canonicalForSig); }
            catch (Exception ignored) {}

            Bitmap barcodeBmp = createBarcodeBitmap(uuid, barcodeW, barcodeH);
            if (barcodeBmp != null) {
                float bx = (w - barcodeW) / 2f;
                canvas.drawBitmap(barcodeBmp, bx, pad, null);
            }

            float byTextSize = Math.max(16f, w / 95f);
            String byLabel = "by: GeoKlik";
            Paint byPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            byPaint.setTextSize(byTextSize);
            byPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
            Paint.FontMetrics byFm = byPaint.getFontMetrics();
            float labelH = (byFm.descent - byFm.ascent);
            float gapQrToLabel = 3f;

            String line1 = safe(project, "PROJECT") + " | " + safe(siteLabel, "UNCAT");
            String line2 = (provinceCity == null || provinceCity.trim().isEmpty())
                    ? "Province, City/Municipality" : provinceCity.trim();

            String latStr = lat == null ? "--" : String.format(Locale.US, "%.6f", lat);
            String lngStr = lng == null ? "--" : String.format(Locale.US, "%.6f", lng);
            String elevStr = "--";
            if (captureLocForExif != null && captureLocForExif.hasAltitude()) {
                elevStr = String.format(Locale.US, "%.1f m", captureLocForExif.getAltitude());
            }

            String latLine = "Lat: " + latStr;
            String lngLine = "Lng: " + lngStr;
            String elevLine = "Elev: " + elevStr;
            String oneLineLatLng = latLine + "  " + lngLine + "  " + elevLine;
            String line4 = datePretty + "  " + timePretty;
            String capName = safe(capturedBy, "Unknown").replace("|", "")
                    .replaceAll("\\s+", " ").trim();
            String line5 = "Captured by: " + capName + " | " + safe(modeToken, "GPS_ONLY");

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

            int safeBottom = pad;
            float bottomBandBottom = h - safeBottom;
            int qrMin = 150;
            int qrMax = 280;
            int qrSize = clamp(w / 7, qrMin, qrMax);
            int logoSize = qrSize;
            Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
            measure.setTextSize(smallSize);

            for (int pass = 0; pass < 2; pass++) {
                int qrX = w - pad - qrSize;
                int logoX = pad;
                float leftTextX = logoX + logoSize + clamp(w / 45, 16, 24);
                float textRightLimit = qrX - clamp(w / 45, 16, 24);
                float maxTextWidth = Math.max(w * 0.30f, textRightLimit - leftTextX);
                boolean latLngOneLine = measure.measureText(oneLineLatLng) <= maxTextWidth;
                boolean line5Wraps = measure.measureText(line5) > maxTextWidth;
                float latLngH = latLngOneLine ? smallStep : (smallStep + smallGap + smallStep);
                float line5H = line5Wraps ? (smallStep + smallGap + smallStep) : smallStep;
                float textBlockH = titleStep + bodyGap + bodyStep + smallGap + latLngH
                        + smallGap + smallStep + (smallGap * 0.30f) + line5H;
                int targetQr = (int) (textBlockH - (labelH + gapQrToLabel));
                qrSize = clamp(targetQr, qrMin, qrMax);
                logoSize = qrSize;
            }

            int qrX = w - pad - qrSize;
            int logoX = pad;
            float leftTextX = logoX + logoSize + clamp(w / 45, 16, 24);
            float textRightLimit = qrX - clamp(w / 45, 16, 24);
            float maxTextWidth = Math.max(w * 0.30f, textRightLimit - leftTextX);
            boolean latLngOneLine = measure.measureText(oneLineLatLng) <= maxTextWidth;
            boolean line5Wraps = measure.measureText(line5) > maxTextWidth;
            float latLngH = latLngOneLine ? smallStep : (smallStep + smallGap + smallStep);
            float line5H = line5Wraps ? (smallStep + smallGap + smallStep) : smallStep;
            float textBlockH = titleStep + bodyGap + bodyStep + smallGap + latLngH
                    + smallGap + smallStep + (smallGap * 0.30f) + line5H;
            float rowH = Math.max(textBlockH, (qrSize + gapQrToLabel + labelH));
            float rowTop = bottomBandBottom - rowH;

            Bitmap qrBmp = createQrBitmap(qrPayload, qrSize, qrSize);
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
                RectF plate = new RectF(cx - platePad, cy - platePad,
                        cx + centerLogo + platePad, cy + centerLogo + platePad);
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
                RectF back = new RectF(qrX - platePad, qrY - platePad,
                        qrX + qrSize + platePad, qrY + qrSize + platePad);
                canvas.drawRoundRect(back, 14f, 14f, qrBack);
                canvas.drawBitmap(qrBmp, qrX, qrY, null);
                float tW = byPaint.measureText(byLabel);
                float textX = qrX + (qrSize / 2f) - (tW / 2f);
                drawSmallItalicText(canvas, byLabel, textX, byY, qrSize, byTextSize);
            }

            if (companyLogo != null) {
                Bitmap scaledLogo = Bitmap.createScaledBitmap(companyLogo, logoSize, logoSize, true);
                int logoY = (int) (bottomBandBottom - logoSize);
                canvas.drawBitmap(scaledLogo, logoX, logoY, null);
            }

            float textTop = Math.max(bottomBandBottom - textBlockH, rowTop);
            float y = textTop - fmTitle.ascent;
            drawTextStrokeNoShadow(canvas, line1, leftTextX, y, titleSize, maxTextWidth);
            y += titleStep + bodyGap;
            drawTextStrokeNoShadow(canvas, ellipsizeText(line2, bodySize, maxTextWidth),
                    leftTextX, y, bodySize, maxTextWidth);
            y += bodyStep + smallGap;

            if (latLngOneLine) {
                drawTextStrokeNoShadow(canvas, oneLineLatLng, leftTextX, y, smallSize, maxTextWidth);
                y += smallStep + smallGap;
            } else {
                drawTextStrokeNoShadow(canvas, latLine, leftTextX, y, smallSize, maxTextWidth);
                y += smallStep + smallGap;
                drawTextStrokeNoShadow(canvas, lngLine + "  " + elevLine,
                        leftTextX, y, smallSize, maxTextWidth);
                y += smallStep + smallGap;
            }

            drawTextStrokeNoShadow(canvas, line4, leftTextX, y, smallSize, maxTextWidth);
            y += smallStep + (smallGap * 0.30f);
            drawWrappedTextStrokeNoShadow(canvas, line5, leftTextX, y,
                    smallSize, maxTextWidth, (smallStep + smallGap), 2);

            try (FileOutputStream out = new FileOutputStream(file)) {
                mutable.compress(Bitmap.CompressFormat.JPEG, 95, out);
                out.flush();
            }

            restoreExifAndWriteGpsAndSig(file, snap, captureLocForExif, signature,
                    uuid, capturedAt, project, capturedBy, modeToken);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to render GeoKlik watermark.", e);
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
            ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_SATURATION, ExifInterface.TAG_SHARPNESS,
            ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_ARTIST, ExifInterface.TAG_IMAGE_DESCRIPTION
    };

    private void restoreExifAndWriteGpsAndSig(File file, ExifSnapshot snap, Location loc,
                                              String sig, String uuid, String capturedAt,
                                              String project, String capturedBy, String modeToken) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            if (snap != null && snap.attrs != null) {
                for (Map.Entry<String, String> e : snap.attrs.entrySet()) {
                    String tag = e.getKey();
                    String val = e.getValue();
                    if (val == null || isGpsTag(tag) || ExifInterface.TAG_USER_COMMENT.equals(tag)) continue;
                    exif.setAttribute(tag, val);
                }
            }

            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,
                    safe(project, "PROJECT") + " - GeoKlik");
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Philmech GeoKlik");

            if (loc != null) {
                exif.setGpsInfo(loc);
                exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, safe(modeToken, "GPS_ONLY"));
            }

            if (capturedAt != null && !capturedAt.trim().isEmpty()) {
                String dt = capturedAt.replace('-', ':');
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt);
                exif.setAttribute(ExifInterface.TAG_DATETIME, dt);
            }

            String comment = "PHILMECH_GEOKLIK"
                    + "\nUUID=" + safe(uuid, "--")
                    + "\nSIG=" + safe(sig, "--")
                    + "\nAT=" + safe(capturedAt, "--")
                    + "\nMODE=" + safe(modeToken, "GPS_ONLY")
                    + "\nELEV=" + getElevationForExifComment(loc)
                    + "\nSATS=" + gnssUsedInFix + "/" + gnssTotal;

            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment);
            exif.setAttribute(ExifInterface.TAG_ARTIST, safe(capturedBy, "Unknown"));
            exif.saveAttributes();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to write GeoKlik EXIF metadata.", e);
        }
    }

    private String getElevationForExifComment(Location loc) {
        try {
            if (loc != null && loc.hasAltitude()) return String.format(Locale.US, "%.1f m", loc.getAltitude());
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

    private String buildCanonicalForSig(String uuid, String project, String site, String description,
                                        Double lat, Double lng, Double acc, String address, String capturedAt) {
        String latStr = lat == null ? "--" : String.format(Locale.US, "%.6f", lat);
        String lngStr = lng == null ? "--" : String.format(Locale.US, "%.6f", lng);
        String accStr = acc == null ? "--" : String.format(Locale.US, "±%.1f m", acc);
        return uuid + "|" + project + "|" + site + "|" + description + "|"
                + latStr + "|" + lngStr + "|" + accStr + "|" + address + "|" + capturedAt;
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
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION,
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H);
            BitMatrix matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, w, h, hints);
            BitMatrix cropped = cropToContent(matrix, 6);
            Bitmap bmp = bitMatrixToBitmap(cropped);
            return Bitmap.createScaledBitmap(bmp, w, h, true);
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap createBarcodeBitmap(String content, int w, int h) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new MultiFormatWriter().encode(content, BarcodeFormat.CODE_128, w, h, hints);
            BitMatrix cropped = cropToContent(matrix, 6);
            Bitmap bmp = bitMatrixToBitmap(cropped);
            return Bitmap.createScaledBitmap(bmp, w, h, true);
        } catch (Exception e) {
            return null;
        }
    }

    private BitMatrix cropToContent(BitMatrix matrix, int pad) {
        int[] rect = matrix.getEnclosingRectangle();
        if (rect == null) return matrix;
        int left = Math.max(0, rect[0] - pad);
        int top = Math.max(0, rect[1] - pad);
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

    private Bitmap bitMatrixToBitmap(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, width, 0, 0, width, height);
        return bmp;
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void drawTextStrokeNoShadow(Canvas canvas, String text, float x, float y,
                                        float textSize, float maxWidth) {
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
                                               float textSize, float maxWidth,
                                               float lineHeight, int maxLines) {
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
            String test = line.length() == 0 ? w : line + " " + w;
            if (measure.measureText(test) > maxWidth) {
                drawTextStrokeNoShadow(canvas, line.toString(), x, yy, textSize, maxWidth);
                yy += lineHeight;
                lines++;
                if (lines >= maxLines - 1) {
                    StringBuilder remaining = new StringBuilder(w);
                    for (int j = i + 1; j < words.length; j++) remaining.append(" ").append(words[j]);
                    drawTextStrokeNoShadow(canvas,
                            ellipsizeText(remaining.toString(), textSize, maxWidth),
                            x, yy, textSize, maxWidth);
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
        while (end > 0 && p.measureText(text, 0, end) + ellW > maxWidth) end--;
        return end <= 0 ? ell : text.substring(0, end) + ell;
    }

    private void drawSmallItalicText(Canvas canvas, String text, float x, float y,
                                     float maxWidth, float textSize) {
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
}
