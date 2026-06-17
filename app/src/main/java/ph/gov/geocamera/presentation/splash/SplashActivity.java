package ph.gov.geocamera.presentation.splash;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import ph.gov.geocamera.data.repository.UserRepository;
import ph.gov.geocamera.presentation.home.HomeActivity;
import ph.gov.geocamera.presentation.permissions.PermissionActivity;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // Install Android 12 splash
        SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);


        UserRepository userRepo = new UserRepository(this);

        // Small delay for smooth branding
        getWindow().getDecorView().postDelayed(() -> {

            if (userRepo.hasUser()) {
                startActivity(new Intent(this, HomeActivity.class));
            } else {
                startActivity(new Intent(this, PermissionActivity.class));
            }

            finish();

        }, 800); // 800ms looks more premium than 600
    }
}