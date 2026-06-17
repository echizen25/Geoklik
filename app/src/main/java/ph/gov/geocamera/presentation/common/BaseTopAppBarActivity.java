package ph.gov.geocamera.presentation.common;

import android.os.Bundle;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import ph.gov.geocamera.R;

public abstract class BaseTopAppBarActivity extends AppCompatActivity {

    @LayoutRes
    protected abstract int getLayoutResId();

    protected abstract String getScreenTitle();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getLayoutResId());

        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        if (toolbar != null) {
            toolbar.setTitle(getScreenTitle());
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24);
            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        }
    }
}