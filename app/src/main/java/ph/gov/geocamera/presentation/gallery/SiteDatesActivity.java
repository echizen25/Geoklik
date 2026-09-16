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
    public static final String EXTRA_SITE_ID="siteId", EXTRA_YEAR="year", EXTRA_SITE_NAME="siteName", EXTRA_CAPTURE_TYPE="captureType", EXTRA_DIVISION_CODE="divisionCode";
    private static final String TYPE_INFRA="INFRA", TYPE_PROJECT="PROJECT", TYPE_ACTIVITY="ACTIVITY", TYPE_PROJECT_ACTIVITY="PROJECT_ACTIVITY", TYPE_PERSONAL="PERSONAL";
    private MaterialToolbar toolbar; private RecyclerView rv; private SwipeRefreshLayout swipeRefresh; private View cardFilter;
    private Spinner spYear, spMonth; private ImageMetaRepository imageRepo; private SiteDatesAdapter adapter;
    private String siteId, siteName, divisionCode="", yearOrAll="ALL", monthOrAll="ALL", captureType=TYPE_INFRA;
    private boolean suppressYear, suppressMonth, filterOpen;

    private static class MonthOption { final String value,label; MonthOption(String v,String l){value=v;label=l;} @Override public String toString(){return label;} }

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_site_dates); imageRepo=new ImageMetaRepository(this);
        toolbar=findViewById(R.id.toolbar); rv=findViewById(R.id.rvDates); swipeRefresh=findViewById(R.id.swipeRefresh); cardFilter=findViewById(R.id.cardFilter); spYear=findViewById(R.id.spYear); spMonth=findViewById(R.id.spMonth);
        Intent i=getIntent(); siteId=i!=null?i.getStringExtra(EXTRA_SITE_ID):null; yearOrAll=i!=null?safe(i.getStringExtra(EXTRA_YEAR),"ALL"):"ALL"; siteName=i!=null?i.getStringExtra(EXTRA_SITE_NAME):null;
        captureType=normalizeCaptureType(i!=null?i.getStringExtra(EXTRA_CAPTURE_TYPE):null); divisionCode=safe(i!=null?i.getStringExtra(EXTRA_DIVISION_CODE):null,"");
        if(siteId==null||siteId.trim().isEmpty()){finish();return;} siteId=siteId.trim();
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24); toolbar.setNavigationOnClickListener(v->finish()); configureToolbarIdentity(); toolbar.setOnMenuItemClickListener(this::onToolbarMenuClick); setPanelVisible(cardFilter,false);
        rv.setLayoutManager(new LinearLayoutManager(this)); rv.setHasFixedSize(true); rv.setItemAnimator(null); rv.setClipToPadding(false);
        rv.addItemDecoration(new RecyclerView.ItemDecoration(){@Override public void getItemOffsets(Rect o,View v,RecyclerView p,RecyclerView.State s){int pos=p.getChildAdapterPosition(v);o.bottom=dp(1);o.top=pos==0?dp(2):0;}});
        adapter=new SiteDatesAdapter(this,captureType,new SiteDatesAdapter.OnClick(){
            @Override public void onClick(SiteDatesAdapter.DateItem d){String gid=d.groupId==null?"":d.groupId.trim();if(gid.isEmpty()){android.widget.Toast.makeText(SiteDatesActivity.this,"No photos for this date.",android.widget.Toast.LENGTH_SHORT).show();return;}String title=d.remarks==null?"":d.remarks.trim();if(title.isEmpty())title=d.sessionDate;Intent n=new Intent(SiteDatesActivity.this,GroupImagesActivity.class);n.putExtra(GroupImagesActivity.EXTRA_GROUP_ID,gid);n.putExtra(GroupImagesActivity.EXTRA_SITE_ID,siteId);n.putExtra(GroupImagesActivity.EXTRA_SESSION_DATE,d.sessionDate);n.putExtra(GroupImagesActivity.EXTRA_DESCRIPTION,title);n.putExtra(GroupImagesActivity.EXTRA_CAPTURE_TYPE,captureType);startActivity(n);}
            @Override public void onRemarksClick(SiteDatesAdapter.DateItem d){showRemarksDialog(d.sessionDate);}
        }); rv.setAdapter(adapter);
        if(swipeRefresh!=null){swipeRefresh.setColorSchemeResources(com.google.android.material.R.color.design_default_color_primary);swipeRefresh.setOnRefreshListener(this::refreshData);rv.addOnScrollListener(new RecyclerView.OnScrollListener(){@Override public void onScrolled(RecyclerView r,int dx,int dy){swipeRefresh.setEnabled(!r.canScrollVertically(-1));}});}
        setupSpinners(); reloadAll();
    }

    private void configureToolbarIdentity(){String title=siteName!=null&&!siteName.trim().isEmpty()?siteName.trim():(TYPE_PERSONAL.equals(captureType)?"Personal Capture":siteId);toolbar.setTitle(title);
        if(TYPE_PERSONAL.equals(captureType))toolbar.setSubtitle("On-device photos");
        else if(TYPE_PROJECT.equals(captureType))toolbar.setSubtitle(divisionCode.isEmpty()?"Project":"Project • "+divisionCode);
        else if(TYPE_ACTIVITY.equals(captureType))toolbar.setSubtitle(divisionCode.isEmpty()?"Activity":"Activity • "+divisionCode);
        else if(TYPE_PROJECT_ACTIVITY.equals(captureType))toolbar.setSubtitle(divisionCode.isEmpty()?"Project Activity":"Project Activity • "+divisionCode);
        else toolbar.setSubtitle("Infrastructure");}

    @Override protected void onResume(){super.onResume();loadDates();}
    private boolean onToolbarMenuClick(MenuItem item){int id=item.getItemId();pressEffect(toolbar);if(id==R.id.action_home){Intent h=new Intent(this,HomeActivity.class);h.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(h);finish();return true;}if(id==R.id.action_filter){toggleFilterPanel();return true;}if(id==R.id.action_geocam){openGeoCam();return true;}return false;}

    private void openGeoCam(){CameraPrefs p=new CameraPrefs(this);CaptureContextRepository c=new CaptureContextRepository(this);Intent cam=new Intent(this,ph.gov.geocamera.presentation.geocamera.GeoCameraActivity.class);
        if(TYPE_PERSONAL.equals(captureType)){p.saveDocumentationType(CameraPrefs.DOC_PERSONAL);p.clearActivityProjectId();p.saveSite(null,true);c.setCurrent(CameraPrefs.DOC_PERSONAL,null);}
        else if(TYPE_PROJECT_ACTIVITY.equals(captureType)){p.saveDocumentationType(CameraPrefs.DOC_PROJECT_ACTIVITY);p.saveActivityProjectId(siteId);p.saveSite(siteId,false);c.setCurrent(CameraPrefs.DOC_PROJECT_ACTIVITY,siteId);cam.putExtra("siteId",siteId);}
        else if(TYPE_PROJECT.equals(captureType)){p.saveDocumentationType(CameraPrefs.DOC_PROJECT);p.clearActivityProjectId();p.saveSite(siteId,false);c.setCurrent(CameraPrefs.DOC_PROJECT,null);cam.putExtra("siteId",siteId);}
        else if(TYPE_ACTIVITY.equals(captureType)){p.saveDocumentationType(CameraPrefs.DOC_ACTIVITY);p.clearActivityProjectId();p.saveSite(siteId,false);c.setCurrent(CameraPrefs.DOC_ACTIVITY,null);cam.putExtra("siteId",siteId);}
        else{p.saveDocumentationType(CameraPrefs.DOC_INFRA);p.clearActivityProjectId();p.saveSite(siteId,false);c.setCurrent(CameraPrefs.DOC_INFRA,null);cam.putExtra("siteId",siteId);}if(siteName!=null)cam.putExtra("siteName",siteName);startActivity(cam);}

    private void toggleFilterPanel(){filterOpen=!filterOpen;if(filterOpen)showDrop(cardFilter);else hideDrop(cardFilter);} private void setPanelVisible(View v,boolean visible){if(v!=null)v.setVisibility(visible?View.VISIBLE:View.GONE);}
    private void showDrop(View v){if(v==null)return;v.animate().cancel();v.setVisibility(View.VISIBLE);v.setAlpha(0f);v.setTranslationY(-dp(10));v.setScaleX(.985f);v.setScaleY(.985f);v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
    private void hideDrop(View v){if(v==null)return;v.animate().cancel();v.animate().alpha(0f).translationY(-dp(8)).scaleX(.985f).scaleY(.985f).setDuration(140).setInterpolator(new android.view.animation.AccelerateInterpolator()).withEndAction(()->{v.setVisibility(View.GONE);v.setAlpha(1f);v.setTranslationY(0f);v.setScaleX(1f);v.setScaleY(1f);}).start();}
    private void pressEffect(View v){if(v!=null)v.animate().scaleX(.985f).scaleY(.985f).alpha(.92f).setDuration(70).withEndAction(()->v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(100).start()).start();}
    private void refreshData(){reloadAll();if(swipeRefresh!=null)swipeRefresh.setRefreshing(false);}

    private void showRemarksDialog(String date){String existing=imageRepo.getGroupRemarksForSiteDate(siteId,date);if(existing==null)existing="";View view=getLayoutInflater().inflate(R.layout.dialog_edit_remarks,null);TextView sub=view.findViewById(R.id.tvDialogSubtitle),info=view.findViewById(R.id.tvDateInfo);com.google.android.material.textfield.TextInputLayout til=view.findViewById(R.id.tilRemarks);com.google.android.material.textfield.TextInputEditText et=view.findViewById(R.id.etRemarks);
        if(TYPE_PROJECT.equals(captureType))sub.setText("Add an album note for this project date");else if(TYPE_ACTIVITY.equals(captureType)||TYPE_PROJECT_ACTIVITY.equals(captureType))sub.setText("Add an album note for this activity date");else if(TYPE_PERSONAL.equals(captureType))sub.setText("Add a note for these personal photos");else sub.setText("Add or update note for this photo date");
        info.setText("Date: "+date);et.setText(existing);et.setSelection(et.getText()!=null?et.getText().length():0);androidx.appcompat.app.AlertDialog dialog=new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setView(view).setNegativeButton("Cancel",null).setNeutralButton("Clear",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(x->{com.google.android.material.button.MaterialButton save=(com.google.android.material.button.MaterialButton)dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);android.widget.Button clear=dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);save.setOnClickListener(v->{String val=et.getText()==null?"":et.getText().toString().trim();if(val.length()>200){til.setError("Maximum 200 characters only.");return;}til.setError(null);imageRepo.updateGroupRemarksForSiteDate(siteId,date,val);loadDates();dialog.dismiss();android.widget.Toast.makeText(this,"Note saved",android.widget.Toast.LENGTH_SHORT).show();});clear.setOnClickListener(v->{et.setText("");til.setError(null);});});dialog.show();}

    private void setupSpinners(){spYear.setOnItemSelectedListener(new SimpleItemSelectedListener(pos->{if(suppressYear)return;String y=(String)spYear.getAdapter().getItem(pos);yearOrAll=safe(y,"ALL");monthOrAll="ALL";loadMonthSpinner();loadDates();}));spMonth.setOnItemSelectedListener(new SimpleItemSelectedListener(pos->{if(suppressMonth)return;MonthOption o=(MonthOption)spMonth.getAdapter().getItem(pos);monthOrAll=o==null?"ALL":o.value;loadDates();}));}
    private void reloadAll(){loadYearSpinner();loadMonthSpinner();loadDates();}
    private void loadYearSpinner(){List<String> years=new ArrayList<>();years.add("ALL");Cursor c=null;try{c=imageRepo.getDistinctYearsForSite(siteId,"ALL");while(c!=null&&c.moveToNext()){String y=c.getString(0);if(y!=null&&!y.trim().isEmpty())years.add(y.trim());}}finally{if(c!=null)c.close();}ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,years);spYear.setAdapter(ad);int sel=0;for(int x=0;x<years.size();x++)if(years.get(x).equalsIgnoreCase(yearOrAll)){sel=x;break;}suppressYear=true;spYear.setSelection(sel,false);suppressYear=false;yearOrAll=years.get(sel);}
    private void loadMonthSpinner(){List<MonthOption> months=new ArrayList<>();months.add(new MonthOption("ALL","ALL"));String[] names={"January","February","March","April","May","June","July","August","September","October","November","December"};for(int n=1;n<=12;n++)months.add(new MonthOption(String.format(Locale.US,"%02d",n),names[n-1]));ArrayAdapter<MonthOption> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,months);spMonth.setAdapter(ad);int sel=0;for(int x=0;x<months.size();x++)if(months.get(x).value.equals(monthOrAll)){sel=x;break;}suppressMonth=true;spMonth.setSelection(sel,false);suppressMonth=false;monthOrAll=months.get(sel).value;}
    private void loadDates(){List<SiteDatesAdapter.DateItem> list=new ArrayList<>();Cursor c=null;try{c=imageRepo.getDatesForSite(siteId,"ALL",yearOrAll,monthOrAll);while(c!=null&&c.moveToNext()){SiteDatesAdapter.DateItem d=new SiteDatesAdapter.DateItem();d.sessionDate=c.getString(0);d.groupId=c.getString(1);d.remarks=c.getString(2);d.totalPhotos=c.getInt(3);d.uploadedPhotos=c.getInt(4);d.uploadingPhotos=c.getInt(5);d.failedPhotos=c.getInt(6);d.pendingPhotos=c.getInt(7);d.unsyncedPhotos=c.getInt(8);d.latestFilename=c.getString(9);d.latestTimestamp=c.getString(10);list.add(d);}}finally{if(c!=null)c.close();}adapter.submit(list);View empty=findViewById(R.id.datesEmptyState);if(empty!=null)empty.setVisibility(list.isEmpty()?View.VISIBLE:View.GONE);if(rv!=null)rv.setVisibility(list.isEmpty()?View.GONE:View.VISIBLE);}
    private static String normalizeCaptureType(String value){String t=value==null?"":value.trim().toUpperCase(Locale.US);if(TYPE_PROJECT.equals(t))return TYPE_PROJECT;if(TYPE_ACTIVITY.equals(t))return TYPE_ACTIVITY;if(TYPE_PROJECT_ACTIVITY.equals(t))return TYPE_PROJECT_ACTIVITY;if(TYPE_PERSONAL.equals(t))return TYPE_PERSONAL;return TYPE_INFRA;}
    private static String safe(String s,String fallback){return s==null||s.trim().isEmpty()?fallback:s.trim();} private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
