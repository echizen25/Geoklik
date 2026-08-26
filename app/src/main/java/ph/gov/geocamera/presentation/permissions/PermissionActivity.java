package ph.gov.geocamera.presentation.permissions;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

import ph.gov.geocamera.R;
import ph.gov.geocamera.presentation.firstlaunch.FirstLaunchActivity;

public class PermissionActivity extends AppCompatActivity {

    private ActivityResultLauncher<String[]> launcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);

        launcher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (hasAllRequired()) {

                        // ✅ Permissions granted, now ensure GPS/Location Services is enabled
                        if (!isLocationEnabled()) {
                            Snackbar.make(findViewById(android.R.id.content),
                                    "Please enable GPS / Location Services.",
                                    Snackbar.LENGTH_LONG).show();

                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                            return;
                        }

                        goNext();
                    } else {
                        Snackbar.make(findViewById(android.R.id.content),
                                "Camera and Location permissions are required.",
                                Snackbar.LENGTH_LONG).show();
                    }
                });

        MaterialButton btn = findViewById(R.id.btnAllow);
        btn.setOnClickListener(v -> launcher.launch(getRequiredPermissions()));

        // ✅ Auto proceed if already granted + GPS enabled
        if (hasAllRequired()) {
            if (!isLocationEnabled()) {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } else {
                goNext();
            }
        }
    }

    // ============================================================
    // Required permissions (based on Android version)
    // ============================================================
    private String[] getRequiredPermissions() {

        List<String> perms = new ArrayList<>();

        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);

        // ✅ Android 9 and below (needed for export to public Pictures)
        if (Build.VERSION.SDK_INT <= 28) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }



        return perms.toArray(new String[0]);
    }

    private boolean hasAllRequired() {
        for (String p : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // ============================================================
    // Check if Location Services / GPS is enabled
    // ============================================================
    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        return lm != null &&
                (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    private void goNext() {
        startActivity(new Intent(this, FirstLaunchActivity.class));
        finish();
    }
}