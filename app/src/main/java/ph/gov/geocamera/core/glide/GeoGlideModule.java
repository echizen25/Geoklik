package ph.gov.geocamera.core.glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;

@GlideModule
public class GeoGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // Default options: slightly better quality for photos
        builder.setDefaultRequestOptions(
                new RequestOptions()
                        .format(DecodeFormat.PREFER_RGB_565) // memory friendly for lots of images
                        .disallowHardwareConfig()            // avoids some issues with large bitmaps
        );
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}