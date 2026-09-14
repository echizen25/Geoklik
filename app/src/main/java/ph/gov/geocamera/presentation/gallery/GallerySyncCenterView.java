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
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.data.sync.SyncScheduler;

/** Minimal Gallery sync counters with a manual Sync All fallback. */
public class GallerySyncCenterView extends MaterialCardView {

    private TextView tvPending;
    private TextView tvSynced;
    private TextView tvFailed;
    private TextView btnSyncAll;
    private ImageMetaRepository imageRepo;
    private boolean observerBound = false;

    public GallerySyncCenterView(@NonNull Context context) {
        super(context);
        init();
    }

    public GallerySyncCenterView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GallerySyncCenterView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        imageRepo = new ImageMetaRepository(getContext().getApplicationContext());
        setRadius(dp(12));
        setCardElevation(0f);
        setStrokeWidth(dp(1));
        setStrokeColor(Color.rgb(225, 234, 230));
        setCardBackgroundColor(Color.WHITE);
        setUseCompatPadding(false);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(8), dp(6));
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        tvPending = makeStat(Color.rgb(198, 106, 0));
        tvSynced = makeStat(Color.rgb(11, 93, 75));
        tvFailed = makeStat(Color.rgb(198, 40, 40));

        row.addView(tvPending, new LinearLayout.LayoutParams(0, dp(30), 1f));
        row.addView(tvSynced, new LinearLayout.LayoutParams(0, dp(30), 1f));
        row.addView(tvFailed, new LinearLayout.LayoutParams(0, dp(30), 1f));

        btnSyncAll = new TextView(getContext());
        btnSyncAll.setText("SYNC ALL");
        btnSyncAll.setTextColor(Color.rgb(11, 93, 75));
        btnSyncAll.setTextSize(9f);
        btnSyncAll.setGravity(Gravity.CENTER);
        btnSyncAll.setTypeface(btnSyncAll.getTypeface(), android.graphics.Typeface.BOLD);
        btnSyncAll.setPadding(dp(10), 0, dp(10), 0);
        btnSyncAll.setMinWidth(dp(66));
        btnSyncAll.setMinHeight(dp(30));

        android.graphics.drawable.GradientDrawable buttonBg = new android.graphics.drawable.GradientDrawable();
        buttonBg.setColor(Color.rgb(239, 248, 244));
        buttonBg.setCornerRadius(dp(15));
        buttonBg.setStroke(dp(1), Color.rgb(190, 220, 208));
        btnSyncAll.setBackground(buttonBg);
        btnSyncAll.setClickable(true);
        btnSyncAll.setFocusable(true);
        row.addView(btnSyncAll, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)));

        btnSyncAll.setOnClickListener(v -> startManualSync());
        renderCounts(0, 0, 0);
    }

    private TextView makeStat(int color) {
        TextView view = new TextView(getContext());
        view.setGravity(Gravity.CENTER);
        view.setTextColor(color);
        view.setTextSize(9f);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setSingleLine(true);
        return view;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
        bindWorkObserver();
    }

    /** Refresh all counters from the local photo database. */
    public void refresh() {
        post(() -> {
            int pending = 0;
            int synced = 0;
            int failed = 0;
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
            while (c != null && c.moveToNext()) {
                // getRootSiteCards column 2 = syncedPhotos for each project/site card.
                total += c.isNull(2) ? 0 : c.getInt(2);
            }
        } finally {
            if (c != null) c.close();
        }
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

        WorkManager.getInstance(getContext())
                .getWorkInfosForUniqueWorkLiveData(SyncScheduler.UNIQUE_UPLOAD_WORK)
                .observe(owner, this::renderWorkState);
    }

    private void renderWorkState(List<WorkInfo> infos) {
        WorkInfo active = null;
        if (infos != null) {
            for (WorkInfo info : infos) {
                if (info == null) continue;
                WorkInfo.State state = info.getState();
                if (state == WorkInfo.State.RUNNING
                        || state == WorkInfo.State.ENQUEUED
                        || state == WorkInfo.State.BLOCKED) {
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

        // UploadWorker updates tbl_imagemeta as each photo succeeds/fails.
        // Re-read the database so the three counters reflect the real saved status.
        refresh();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
