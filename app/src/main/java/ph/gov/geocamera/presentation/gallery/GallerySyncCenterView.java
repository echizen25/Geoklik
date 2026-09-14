package ph.gov.geocamera.presentation.gallery;

import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
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

/** Compact operational sync summary shown only in Gallery. */
public class GallerySyncCenterView extends MaterialCardView {

    private TextView tvSummary;
    private TextView tvPending;
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
        setRadius(dp(14));
        setCardElevation(dp(1));
        setStrokeWidth(dp(1));
        setStrokeColor(Color.rgb(217, 231, 225));
        setCardBackgroundColor(Color.WHITE);
        setUseCompatPadding(false);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        top.addView(labels, labelsLp);

        TextView title = new TextView(getContext());
        title.setText("SYNC CENTER");
        title.setTextColor(Color.rgb(11, 93, 75));
        title.setTextSize(11f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        labels.addView(title);

        tvSummary = new TextView(getContext());
        tvSummary.setText("Checking photo sync status...");
        tvSummary.setTextColor(Color.rgb(100, 116, 110));
        tvSummary.setTextSize(10f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(2);
        labels.addView(tvSummary, summaryLp);

        btnSyncAll = new TextView(getContext());
        btnSyncAll.setText("SYNC ALL");
        btnSyncAll.setTextColor(Color.rgb(11, 93, 75));
        btnSyncAll.setTextSize(10f);
        btnSyncAll.setGravity(Gravity.CENTER);
        btnSyncAll.setTypeface(btnSyncAll.getTypeface(), android.graphics.Typeface.BOLD);
        btnSyncAll.setPadding(dp(12), 0, dp(12), 0);
        btnSyncAll.setMinWidth(dp(74));
        btnSyncAll.setMinHeight(dp(36));
        android.graphics.drawable.GradientDrawable buttonBg = new android.graphics.drawable.GradientDrawable();
        buttonBg.setColor(Color.rgb(239, 248, 244));
        buttonBg.setCornerRadius(dp(18));
        buttonBg.setStroke(dp(1), Color.rgb(190, 220, 208));
        btnSyncAll.setBackground(buttonBg);
        btnSyncAll.setClickable(true);
        btnSyncAll.setFocusable(true);
        top.addView(btnSyncAll, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)));

        LinearLayout stats = new LinearLayout(getContext());
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statsLp.topMargin = dp(8);
        root.addView(stats, statsLp);

        tvPending = makeStat(Color.rgb(198, 106, 0));
        tvFailed = makeStat(Color.rgb(198, 40, 40));
        stats.addView(tvPending, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        View divider = new View(getContext());
        divider.setBackgroundColor(Color.rgb(228, 236, 232));
        stats.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(24)));

        stats.addView(tvFailed, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        btnSyncAll.setOnClickListener(v -> startManualSync());
    }

    private TextView makeStat(int color) {
        TextView view = new TextView(getContext());
        view.setGravity(Gravity.CENTER);
        view.setTextColor(color);
        view.setTextSize(10f);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
        bindWorkObserver();
    }

    public void refresh() {
        post(() -> {
            int pending = 0;
            int failed = 0;
            try {
                pending = imageRepo.countPendingForSync();
                failed = imageRepo.countFailedForSyncCenter();
            } catch (Exception ignored) { }

            tvPending.setText(pending + " Pending");
            tvFailed.setText(failed + " Failed");

            if (pending == 0 && failed == 0) {
                tvSummary.setText("All uploadable photos are synced");
            } else if (failed > 0) {
                tvSummary.setText(failed + " failed • review items before retrying");
            } else {
                tvSummary.setText(pending + " waiting for automatic upload");
            }
        });
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
        tvSummary.setText("Preparing " + pending + " photo(s)...");
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
        int done = active.getProgress().getInt("DONE", 0);
        int total = active.getProgress().getInt("TOTAL", 0);
        if (active.getState() == WorkInfo.State.RUNNING) {
            tvSummary.setText(total > 0 ? "Uploading photos • " + done + "/" + total : "Uploading photos...");
        } else if (active.getState() == WorkInfo.State.BLOCKED) {
            tvSummary.setText("Sync waiting for requirements...");
        } else {
            tvSummary.setText("Sync queued...");
        }
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
