package ph.gov.geocamera.presentation.gallery;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.repository.CaptureContextRepository;
import ph.gov.geocamera.data.repository.ImageMetaRepository;
import ph.gov.geocamera.presentation.home.HomeActivity;

public class SiteDatesActivity extends AppCompatActivity {

    public static final String EXTRA_SITE_ID = "siteId";
    public static final String EXTRA_YEAR = "year";
    public static final String EXTRA_SITE_NAME = "siteName";
    public static final String EXTRA_CAPTURE_TYPE = "captureType";
    public static final String EXTRA_DIVISION_CODE = "divisionCode";

    private static final String TYPE_INFRA = "INFRA";
    private static final String TYPE_ACTIVITY = "ACTIVITY";
    private static final String TYPE_PROJECT_ACTIVITY = "PROJECT_ACTIVITY";
    private static final String TYPE_PERSONAL = "PERSONAL";

    private MaterialToolbar toolbar;
    private RecyclerView rv;
    private SwipeRefreshLayout swipeRefresh;
    private View cardFilter;
    private Spinner spYear, spMonth;
    private ImageMetaRepository imageRepo;
    private SiteDatesAdapter adapter;
    private String siteId;
    private String yearOrAll = "ALL";
    private String monthOrAll = "ALL";
    private String siteName = null;
    private String captureType = TYPE_INFRA;
    private String divisionCode = "";
    private boolean suppressYear = false;
    private boolean suppressMonth = false;
    private boolean filterOpen = false;

    private static class MonthOption {
        final String value; final String label;
        MonthOption(String value, String label) { this.value = value; this.label = label; }
        @Override public String toString() { return label; }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_site_dates);
        imageRepo = new ImageMetaRepository(this);
        toolbar = findViewById(R.id.toolbar); rv = findViewById(R.id.rvDates); swipeRefresh = findViewById(R.id.swipeRefresh);
        cardFilter = findViewById(R.id.cardFilter); spYear = findViewById(R.id.spYear); spMonth = findViewById(R.id.spMonth);
        Intent i = getIntent();
        siteId = i != null ? i.getStringExtra(EXTRA_SITE_ID) : null;
        yearOrAll = i != null ? safe(i.getStringExtra(EXTRA_YEAR), "ALL") : "ALL";
        siteName = i != null ? i.getStringExtra(EXTRA_SITE_NAME) : null;
        captureType = normalizeCaptureType(i != null ? i.getStringExtra(EXTRA_CAPTURE_TYPE) : null);
        divisionCode = safe(i != null ? i.getStringExtra(EXTRA_DIVISION_CODE) : null, "");
        if (siteId == null || siteId.trim().isEmpty()) { finish(); return; }
        siteId = siteId.trim();
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24); toolbar.setNavigationOnClickListener(v -> finish()); configureToolbarIdentity(); toolbar.setOnMenuItemClickListener(this::onToolbarMenuClick);
        setPanelVisible(cardFilter, false);
        LinearLayoutManager lm = new LinearLayoutManager(this); rv.setLayoutManager(lm); rv.setHasFixedSize(true); rv.setItemAnimator(null); rv.setClipToPadding(false);
        rv.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                outRect.left = 0; outRect.right = 0; outRect.bottom = dp(1); outRect.top = position == 0 ? dp(2) : 0;
            }
        });
        adapter = new SiteDatesAdapter(this, captureType, new SiteDatesAdapter.OnClick() {
            @Override public void onClick(SiteDatesAdapter.DateItem dateItem) {
                String groupId = dateItem.groupId == null ? "" : dateItem.groupId.trim();
                if (groupId.isEmpty()) { android.widget.Toast.makeText(SiteDatesActivity.this, "No photos for this date.", android.widget.Toast.LENGTH_SHORT).show(); return; }
                String title = dateItem.remarks == null ? "" : dateItem.remarks.trim(); if (title.isEmpty()) title = dateItem.sessionDate;
                Intent next = new Intent(SiteDatesActivity.this, GroupImagesActivity.class);
                next.putExtra(GroupImagesActivity.EXTRA_GROUP_ID, groupId); next.putExtra(GroupImagesActivity.EXTRA_SITE_ID, siteId); next.putExtra(GroupImagesActivity.EXTRA_SESSION_DATE, dateItem.sessionDate); next.putExtra(GroupImagesActivity.EXTRA_DESCRIPTION, title); next.putExtra(GroupImagesActivity.EXTRA_CAPTURE_TYPE, captureType); startActivity(next);
            }
            @Override public void onRemarksClick(SiteDatesAdapter.DateItem dateItem) { showRemarksDialog(dateItem.sessionDate); }
        });
        rv.setAdapter(adapter);
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeResources(com.google.android.material.R.color.design_default_color_primary); swipeRefresh.setOnRefreshListener(this::refreshData);
            rv.addOnScrollListener(new RecyclerView.OnScrollListener() { @Override public void onScrolled(RecyclerView recyclerView, int dx, int dy) { swipeRefresh.setEnabled(!recyclerView.canScrollVertically(-1)); } });
        }
        setupSpinners(); reloadAll();
    }

    private void configureToolbarIdentity() {
        String title = siteName != null && !siteName.trim().isEmpty() ? siteName.trim() : (TYPE_PERSONAL.equals(captureType) ? "Personal Capture" : siteId); toolbar.setTitle(title);
        if (TYPE_PERSONAL.equals(captureType)) toolbar.setSubtitle("On-device photos");
        else if (TYPE_ACTIVITY.equals(captureType)) toolbar.setSubtitle(divisionCode.isEmpty() ? "Activity" : "Activity • " + divisionCode);
        else if (TYPE_PROJECT_ACTIVITY.equals(captureType)) toolbar.setSubtitle(divisionCode.isEmpty() ? "Project Activity" : "Project Activity • " + divisionCode);
        else toolbar.setSubtitle("Infrastructure");
    }

    @Override protected void onResume() { super.onResume(); loadDates(); }
    private boolean onToolbarMenuClick(MenuItem item) {
        int id = item.getItemId(); pressEffect(toolbar);
        if (id == R.id.action_home) { Intent h = new Intent(this, HomeActivity.class); h.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(h); finish(); return true; }
        if (id == R.id.action_filter) { toggleFilterPanel(); return true; }
        if (id == R.id.action_geocam) { openGeoCam(); return true; }
        return false;
    }

    private void openGeoCam() {
        CameraPrefs prefs = new CameraPrefs(this); CaptureContextRepository captureContext = new CaptureContextRepository(this); Intent cam = new Intent(this, ph.gov.geocamera.presentation.geocamera.GeoCameraActivity.class);
        if (TYPE_PERSONAL.equals(captureType)) { prefs.saveDocumentationType(CameraPrefs.DOC_PERSONAL); prefs.clearActivityProjectId(); prefs.saveSite(null, true); captureContext.setCurrent(CameraPrefs.DOC_PERSONAL, null); }
        else if (TYPE_PROJECT_ACTIVITY.equals(captureType)) { prefs.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY); prefs.saveActivityProjectId(siteId); prefs.saveSite(siteId, false); captureContext.setCurrent(CameraPrefs.DOC_PROJECT_ACTIVITY, siteId); cam.putExtra("siteId", siteId); }
        else if (TYPE_ACTIVITY.equals(captureType)) { prefs.saveDocumentationType(CameraPrefs.DOC_ACTIVITY); prefs.clearActivityProjectId(); prefs.saveSite(siteId, false); captureContext.setCurrent(CameraPrefs.DOC_ACTIVITY, null); cam.putExtra("siteId", siteId); }
        else { prefs.saveDocumentationType(CameraPrefs.DOC_INFRA); prefs.clearActivityProjectId(); prefs.saveSite(siteId, false); captureContext.setCurrent(CameraPrefs.DOC_INFRA, null); cam.putExtra("siteId", siteId); }
        if (siteName != null) cam.putExtra("siteName", siteName); startActivity(cam);
    }

    private void toggleFilterPanel() { filterOpen = !filterOpen; if (filterOpen) showDrop(cardFilter); else hideDrop(cardFilter); }
    private void setPanelVisible(View v, boolean visible) { if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE); }
    private void showDrop(View v) { if (v == null) return; v.animate().cancel(); v.setVisibility(View.VISIBLE); v.setAlpha(0f); v.setTranslationY(-dp(10)); v.setScaleX(.985f); v.setScaleY(.985f); v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start(); }
    private void hideDrop(View v) { if (v == null) return; v.animate().cancel(); v.animate().alpha(0f).translationY(-dp(8)).scaleX(.985f).scaleY(.985f).setDuration(140).setInterpolator(new android.view.animation.AccelerateInterpolator()).withEndAction(() -> { v.setVisibility(View.GONE); v.setAlpha(1f); v.setTranslationY(0f); v.setScaleX(1f); v.setScaleY(1f); }).start(); }
    private void pressEffect(View v) { if (v == null) return; v.animate().scaleX(.985f).scaleY(.985f).alpha(.92f).setDuration(70).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(100).start()).start(); }
    private void refreshData() { reloadAll(); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); }

    private void showRemarksDialog(String sessionDate) {
        String existing = imageRepo.getGroupRemarksForSiteDate(siteId, sessionDate); if (existing == null) existing = "";
        View view = getLayoutInflater().inflate(R.layout.dialog_edit_remarks, null); TextView tvDialogSubtitle = view.findViewById(R.id.tvDialogSubtitle); TextView tvDateInfo = view.findViewById(R.id.tvDateInfo);
        com.google.android.material.textfield.TextInputLayout tilRemarks = view.findViewById(R.id.tilRemarks); com.google.android.material.textfield.TextInputEditText etRemarks = view.findViewById(R.id.etRemarks);
        if (TYPE_ACTIVITY.equals(captureType) || TYPE_PROJECT_ACTIVITY.equals(captureType)) tvDialogSubtitle.setText("Add an album note for this activity date"); else if (TYPE_PERSONAL.equals(captureType)) tvDialogSubtitle.setText("Add a note for these personal photos"); else tvDialogSubtitle.setText("Add or update note for this photo date");
        tvDateInfo.setText("Date: " + sessionDate); etRemarks.setText(existing); etRemarks.setSelection(etRemarks.getText() != null ? etRemarks.getText().length() : 0);
        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setView(view).setNegativeButton("Cancel", null).setNeutralButton("Clear", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> { com.google.android.material.button.MaterialButton btnSave = (com.google.android.material.button.MaterialButton) dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE); android.widget.Button btnClear = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
            btnSave.setOnClickListener(v -> { String val = etRemarks.getText() == null ? "" : etRemarks.getText().toString().trim(); if (val.length() > 200) { tilRemarks.setError("Maximum 200 characters only."); return; } tilRemarks.setError(null); imageRepo.updateGroupRemarksForSiteDate(siteId, sessionDate, val); loadDates(); dialog.dismiss(); android.widget.Toast.makeText(this, "Note saved", android.widget.Toast.LENGTH_SHORT).show(); });
            btnClear.setOnClickListener(v -> { etRemarks.setText(""); tilRemarks.setError(null); }); }); dialog.show();
    }

    private void setupSpinners() {
        spYear.setOnItemSelectedListener(new SimpleItemSelectedListener(pos -> { if (suppressYear) return; String newYear = (String) spYear.getAdapter().getItem(pos); yearOrAll = safe(newYear, "ALL"); monthOrAll = "ALL"; loadMonthSpinner(); loadDates(); }));
        spMonth.setOnItemSelectedListener(new SimpleItemSelectedListener(pos -> { if (suppressMonth) return; MonthOption opt = (MonthOption) spMonth.getAdapter().getItem(pos); monthOrAll = opt == null ? "ALL" : opt.value; loadDates(); }));
    }
    private void reloadAll() { loadYearSpinner(); loadMonthSpinner(); loadDates(); }
    private void loadYearSpinner() { List<String> years = new ArrayList<>(); years.add("ALL"); Cursor yc = null; try { yc = imageRepo.getDistinctYearsForSite(siteId, "ALL"); while (yc != null && yc.moveToNext()) { String y = yc.getString(0); if (y != null && !y.trim().isEmpty()) years.add(y.trim()); } } finally { if (yc != null) yc.close(); } ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, years); spYear.setAdapter(ad); int sel=0; for(int idx=0;idx<years.size();idx++) if(years.get(idx).equalsIgnoreCase(yearOrAll)){sel=idx;break;} suppressYear=true;spYear.setSelection(sel,false);suppressYear=false;yearOrAll=years.get(sel); }
    private void loadMonthSpinner() { List<MonthOption> months=new ArrayList<>(); months.add(new MonthOption("ALL","ALL")); String[] names={"January","February","March","April","May","June","July","August","September","October","November","December"}; for(int n=1;n<=12;n++)months.add(new MonthOption(String.format(Locale.US,"%02d",n),names[n-1])); ArrayAdapter<MonthOption> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,months); spMonth.setAdapter(ad); int sel=0; for(int idx=0;idx<months.size();idx++)if(months.get(idx).value.equals(monthOrAll)){sel=idx;break;} suppressMonth=true;spMonth.setSelection(sel,false);suppressMonth=false;monthOrAll=months.get(sel).value; }
    private void loadDates() { List<SiteDatesAdapter.DateItem> list=new ArrayList<>(); Cursor c=null; try { c=imageRepo.getDateCardsForSite(siteId,yearOrAll,monthOrAll); while(c!=null&&c.moveToNext()){ SiteDatesAdapter.DateItem d=new SiteDatesAdapter.DateItem(); d.sessionDate=c.getString(0);d.groupId=c.getString(1);d.totalPhotos=c.getInt(2);d.uploadedPhotos=c.getInt(3);d.uploadingPhotos=c.getInt(4);d.failedPhotos=c.getInt(5);d.pendingPhotos=c.getInt(6);d.unsyncedPhotos=c.getInt(7);d.latestFilename=c.getString(8);d.latestTimestamp=c.getString(9);d.remarks=c.getString(10);list.add(d);} } finally {if(c!=null)c.close();} adapter.submit(list); View empty=findViewById(R.id.datesEmptyState); if(empty!=null)empty.setVisibility(list.isEmpty()?View.VISIBLE:View.GONE); if(rv!=null)rv.setVisibility(list.isEmpty()?View.GONE:View.VISIBLE); }
    private static String normalizeCaptureType(String value){String type=value==null?"":value.trim().toUpperCase(Locale.US);if(TYPE_ACTIVITY.equals(type))return TYPE_ACTIVITY;if(TYPE_PROJECT_ACTIVITY.equals(type))return TYPE_PROJECT_ACTIVITY;if(TYPE_PERSONAL.equals(type))return TYPE_PERSONAL;return TYPE_INFRA;}
    private static String safe(String s,String fallback){return s==null||s.trim().isEmpty()?fallback:s.trim();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
