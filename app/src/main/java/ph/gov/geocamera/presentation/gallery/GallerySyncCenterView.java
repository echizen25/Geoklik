package ph.gov.geocamera.presentation.gallery;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

import ph.gov.geocamera.R;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.data.sync.SyncScheduler;

/** Compact Gallery sync counters with a manual Sync All fallback. */
public class GallerySyncCenterView extends MaterialCardView {

    private TextView tvPending;
    private TextView tvSynced;
    private TextView tvFailed;
    private TextView btnSyncAll;
    private ImageMetaRepository imageRepo;
    private boolean observerBound = false;

    public GallerySyncCenterView(@NonNull Context context) { super(context); init(); }
    public GallerySyncCenterView(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public GallerySyncCenterView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        imageRepo = new ImageMetaRepository(getContext().getApplicationContext());
        setRadius(dp(11));
        setCardElevation(0f);
        setStrokeWidth(dp(1));
        setStrokeColor(ContextCompat.getColor(getContext(), R.color.app_brand_stroke_subtle));
        setCardBackgroundColor(ContextCompat.getColor(getContext(), R.color.app_surface_variant));
        setUseCompatPadding(false);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(7), dp(4), dp(6), dp(4));
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        tvPending = makeStat(ContextCompat.getColor(getContext(), R.color.status_pending_text));
        tvSynced = makeStat(ContextCompat.getColor(getContext(), R.color.status_synced_text));
        tvFailed = makeStat(Color.rgb(198, 40, 40));

        row.addView(tvPending, new LinearLayout.LayoutParams(0, dp(26), 1f));
        row.addView(tvSynced, new LinearLayout.LayoutParams(0, dp(26), 1f));
        row.addView(tvFailed, new LinearLayout.LayoutParams(0, dp(26), 1f));

        btnSyncAll = new TextView(getContext());
        btnSyncAll.setText("SYNC ALL");
        btnSyncAll.setTextColor(ContextCompat.getColor(getContext(), R.color.brand_green_ui));
        btnSyncAll.setTextSize(8f);
        btnSyncAll.setGravity(Gravity.CENTER);
        btnSyncAll.setTypeface(btnSyncAll.getTypeface(), android.graphics.Typeface.BOLD);
        btnSyncAll.setPadding(dp(8), 0, dp(8), 0);
        btnSyncAll.setMinWidth(dp(58));
        btnSyncAll.setMinHeight(dp(26));

        android.graphics.drawable.GradientDrawable buttonBg = new android.graphics.drawable.GradientDrawable();
        buttonBg.setColor(ContextCompat.getColor(getContext(), R.color.brand_green_soft));
        buttonBg.setCornerRadius(dp(13));
        buttonBg.setStroke(dp(1), ContextCompat.getColor(getContext(), R.color.brand_green_soft_stroke));
        btnSyncAll.setBackground(buttonBg);
        btnSyncAll.setClickable(true);
        btnSyncAll.setFocusable(true);
        row.addView(btnSyncAll, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(26)));

        btnSyncAll.setOnClickListener(v -> startManualSync());
        renderCounts(0, 0, 0);
    }

    private TextView makeStat(int color) {
        TextView view = new TextView(getContext());
        view.setGravity(Gravity.CENTER);
        view.setTextColor(color);
        view.setTextSize(8f);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setSingleLine(true);
        return view;
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); refresh(); bindWorkObserver(); }

    public void refresh() {
        post(() -> {
            int pending = 0, synced = 0, failed = 0;
            try {
                pending = imageRepo.countPendingForSync();
                synced = countSyncedPhotos();
                failed = imageRepo.countFailedForSyncCenter();
            } catch (Exception ignored) { }
            renderCounts(pending, synced, failed);
        });
    }

    private int countSyncedPhotos() {
        Cursor c = null;
        int total = 0;
        try {
            c = imageRepo.getRootSiteCards("ALL", "ALL");
            while (c != null && c.moveToNext()) total += c.isNull(2) ? 0 : c.getInt(2);
        } finally { if (c != null) c.close(); }
        return total;
    }

    private void renderCounts(int pending, int synced, int failed) {
        tvPending.setText(pending + " Pending");
        tvSynced.setText(synced + " Synced");
        tvFailed.setText(failed + " Failed");
    }

    private void startManualSync() {
        if (!isOnline()) {
            Toast.makeText(getContext(), "No internet connection. Sync unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }
        int pending = imageRepo.countPendingForSync();
        if (pending <= 0) {
            int failed = imageRepo.countFailedForSyncCenter();
            Toast.makeText(getContext(), failed > 0 ? "No retryable photos. Review failed items." : "Nothing to sync.", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        btnSyncAll.setEnabled(false);
        btnSyncAll.setText("SYNCING");
        refresh();
        SyncScheduler.enqueueUploadNow(getContext().getApplicationContext());
    }

    private void bindWorkObserver() {
        if (observerBound) return;
        LifecycleOwner owner = ViewTreeLifecycleOwner.get(this);
        if (owner == null) return;
        observerBound = true;
        WorkManager.getInstance(getContext()).getWorkInfosForUniqueWorkLiveData(SyncScheduler.UNIQUE_UPLOAD_WORK)
                .observe(owner, this::renderWorkState);
    }

    private void renderWorkState(List<WorkInfo> infos) {
        WorkInfo active = null;
        if (infos != null) {
            for (WorkInfo info : infos) {
                if (info == null) continue;
                WorkInfo.State state = info.getState();
                if (state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED) {
                    active = info;
                    break;
                }
            }
        }
        if (active == null) {
            btnSyncAll.setEnabled(true);
            btnSyncAll.setText("SYNC ALL");
            refresh();
            return;
        }
        btnSyncAll.setEnabled(false);
        btnSyncAll.setText("SYNCING");
        refresh();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
