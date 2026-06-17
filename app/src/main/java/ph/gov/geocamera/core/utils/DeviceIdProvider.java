package ph.gov.geocamera.core.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.provider.Settings;

import java.util.UUID;

public class DeviceIdProvider {

    private static final String PREFS_NAME = "geo_prefs";
    private static final String KEY_DEVICE_ID = "stored_device_id";

    public static String getOrCreateDeviceId(Context context) {

        // 1️⃣ If already saved, return it
        String saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DEVICE_ID, null);

        if (saved != null) return saved;

        // 2️⃣ Try ANDROID_ID
        @SuppressLint("HardwareIds") String androidId = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );

        String finalId;

        if (androidId != null && !androidId.isEmpty()) {
            finalId = androidId;
        } else {
            // 3️⃣ Fallback to UUID (rare case)
            finalId = UUID.randomUUID().toString();
        }

        // 4️⃣ Save permanently (so it never changes)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DEVICE_ID, finalId)
                .apply();

        return finalId;
    }
}