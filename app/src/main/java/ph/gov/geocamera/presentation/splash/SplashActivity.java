package ph.gov.geocamera.presentation.splash;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ph.gov.geocamera.data.remote.AppVersionService;
import ph.gov.geocamera.data.repository.UserRepository;
import ph.gov.geocamera.presentation.home.HomeActivity;
import ph.gov.geocamera.presentation.permissions.PermissionActivity;

public class SplashActivity extends AppCompatActivity {

    private UserRepository userRepo;
    private boolean navigationStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        userRepo = new UserRepository(this);
        getWindow().getDecorView().postDelayed(this::checkAppVersion, 650);
    }

    private void checkAppVersion() {
        new AppVersionService(this).check((policy, fromServer) -> {
            if (isFinishing() || isDestroyed()) return;

            // Only a live endpoint response can show/enforce an update.
            if (!fromServer || policy == null) {
                continueToApp();
                return;
            }

            int installedVersionCode = getInstalledVersionCode();

            if (policy.isUpdateRequired(installedVersionCode)) {
                showRequiredUpdate(policy);
                return;
            }

            if (policy.isUpdateAvailable(installedVersionCode)) {
                showOptionalUpdate(policy);
                return;
            }

            continueToApp();
        });
    }

    private int getInstalledVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            return code > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) code;
        } catch (Exception ignored) {
            // Fail open instead of blocking field use if package metadata fails.
            return Integer.MAX_VALUE;
        }
    }

    private void showRequiredUpdate(AppVersionService.VersionPolicy policy) {
        String versionText = policy.latestVersionName.isEmpty()
                ? ""
                : "\n\nLatest version: " + policy.latestVersionName;

        new MaterialAlertDialogBuilder(this)
                .setTitle("GeoKlik Update Required")
                .setMessage(policy.message + versionText +
                        "\n\nPlease update GeoKlik to continue using the application.")
                .setCancelable(false)
                .setPositiveButton("UPDATE NOW", (dialog, which) -> openPlayStore())
                .show();
    }

    private void showOptionalUpdate(AppVersionService.VersionPolicy policy) {
        String versionText = policy.latestVersionName.isEmpty()
                ? ""
                : "\n\nLatest version: " + policy.latestVersionName;

        new MaterialAlertDialogBuilder(this)
                .setTitle("GeoKlik Update Available")
                .setMessage(policy.message + versionText)
                .setPositiveButton("UPDATE NOW", (dialog, which) -> openPlayStore())
                .setNegativeButton("LATER", (dialog, which) -> continueToApp())
                .setOnCancelListener(dialog -> continueToApp())
                .show();
    }

    private void openPlayStore() {
        String packageName = getPackageName();

        try {
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + packageName)
            ));
        } catch (ActivityNotFoundException ex) {
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)
            ));
        }
    }

    private void continueToApp() {
        if (navigationStarted || isFinishing() || isDestroyed()) return;
        navigationStarted = true;

        if (userRepo.hasUser()) {
            startActivity(new Intent(this, HomeActivity.class));
        } else {
            startActivity(new Intent(this, PermissionActivity.class));
        }

        finish();
    }
}
