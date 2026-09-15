package ph.gov.geocamera.presentation.site;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.ListPopupWindow;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.local.db.GeoDbHelper;

/**
 * Compact Top-5 project dropdown for Change Project.
 *
 * The closed state occupies only one small row. Suggestions are shown in a
 * temporary dropdown so long project names/beneficiaries do not make the
 * Change Project screen tall. Data comes only from the synced local cache and
 * works offline. Search matches Code, Site ID, Project Name and Beneficiary.
 */
public class ProjectSuggestionsView extends LinearLayout {

    private static final int LIMIT = 5;

    private TextInputEditText input;
    private MaterialButton trigger;
    private GeoDbHelper dbHelper;
    private String requiredProjectType = "";
    private List<ProjectHit> currentHits = new ArrayList<>();
    private ListPopupWindow popup;

    public ProjectSuggestionsView(@NonNull Context context) {
        super(context);
        init();
    }

    public ProjectSuggestionsView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ProjectSuggestionsView(@NonNull Context context,
                                  @Nullable AttributeSet attrs,
                                  int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        dbHelper = new GeoDbHelper(getContext().getApplicationContext());

        trigger = new MaterialButton(getContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        trigger.setAllCaps(false);
        trigger.setText("Suggested projects");
        trigger.setTextSize(12f);
        trigger.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        trigger.setMinHeight(dp(42));
        trigger.setMinimumHeight(dp(42));
        trigger.setInsetTop(0);
        trigger.setInsetBottom(0);
        trigger.setPadding(dp(12), 0, dp(12), 0);
        trigger.setCornerRadius(dp(12));
        trigger.setOnClickListener(v -> showDropdown());

        addView(trigger, new LayoutParams(LayoutParams.MATCH_PARENT, dp(42)));
        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        View root = getRootView();
        View field = root == null ? null : root.findViewById(R.id.actSite);
        if (!(field instanceof TextInputEditText)) return;
        input = (TextInputEditText) field;

        Context c = getContext();
        if (c instanceof android.app.Activity) {
            Intent intent = ((android.app.Activity) c).getIntent();
            if (intent != null && intent.getBooleanExtra(SetSiteActivity.EXTRA_PICK_ONLY, false)) {
                requiredProjectType = normalizeType(
                        intent.getStringExtra(SetSiteActivity.EXTRA_REQUIRED_PROJECT_TYPE));
            }
        }

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshSuggestions(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Empty query = Top 5 cached projects. Typing changes this to Top 5 matches.
        refreshSuggestions(input.getText() == null ? "" : input.getText().toString());
    }

    @Override
    protected void onDetachedFromWindow() {
        if (popup != null) popup.dismiss();
        super.onDetachedFromWindow();
    }

    private void refreshSuggestions(String query) {
        currentHits = queryLocalProjects(query, LIMIT);
        if (popup != null) popup.dismiss();

        if (currentHits.isEmpty()) {
            setVisibility(GONE);
            return;
        }

        boolean searching = !clean(query).isEmpty();
        trigger.setText((searching ? "Top matches" : "Suggested projects")
                + "  •  " + currentHits.size() + "   ▾");
        trigger.setContentDescription((searching ? "Top project matches" : "Suggested projects")
                + ", " + currentHits.size() + ". Double tap to open dropdown.");
        setVisibility(VISIBLE);
    }

    private void showDropdown() {
        if (currentHits == null || currentHits.isEmpty()) return;

        if (popup != null) popup.dismiss();
        popup = new ListPopupWindow(getContext());
        popup.setAnchorView(trigger);
        popup.setModal(true);
        popup.setAdapter(new ProjectHitAdapter(currentHits));

        int available = Math.max(dp(220), getResources().getDisplayMetrics().widthPixels - dp(52));
        int anchorWidth = getWidth();
        popup.setWidth(anchorWidth > 0 ? anchorWidth : available);
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < currentHits.size()) {
                ProjectHit hit = currentHits.get(position);
                popup.dismiss();
                choose(hit);
            }
        });
        popup.show();
    }

    private void choose(ProjectHit hit) {
        if (input == null) return;
        String value = clean(hit.code);
        if (value.isEmpty()) value = clean(hit.projectId);
        if (value.isEmpty()) return;

        input.setText(value);
        input.setSelection(value.length());
        input.clearFocus();

        // Keep the existing verified selection path: Project Code resolves back
        // to the stored Site ID/projectid before the camera context is changed.
        input.onEditorAction(EditorInfo.IME_ACTION_DONE);
    }

    private List<ProjectHit> queryLocalProjects(String rawQuery, int limit) {
        List<ProjectHit> result = new ArrayList<>();
        String q = clean(rawQuery);
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = null;
        try {
            String typeWhere = requiredProjectType.isEmpty()
                    ? ""
                    : " AND upper(trim(project_type)) = ? ";

            if (q.isEmpty()) {
                String sql = "SELECT projectid, code, coda, beneficiary, project_type, timestamp " +
                        "FROM tbl_projects WHERE 1=1 " + typeWhere +
                        "ORDER BY CASE WHEN timestamp IS NULL OR trim(timestamp)='' THEN 1 ELSE 0 END, " +
                        "timestamp DESC, code COLLATE NOCASE ASC LIMIT ?";
                String[] args = requiredProjectType.isEmpty()
                        ? new String[]{String.valueOf(limit)}
                        : new String[]{requiredProjectType, String.valueOf(limit)};
                c = db.rawQuery(sql, args);
            } else {
                String like = "%" + q + "%";
                String prefix = q + "%";
                String sql = "SELECT projectid, code, coda, beneficiary, project_type, timestamp " +
                        "FROM tbl_projects WHERE (" +
                        "code LIKE ? COLLATE NOCASE OR projectid LIKE ? COLLATE NOCASE OR " +
                        "coda LIKE ? COLLATE NOCASE OR beneficiary LIKE ? COLLATE NOCASE) " +
                        typeWhere +
                        "ORDER BY CASE " +
                        "WHEN trim(code)=trim(?) COLLATE NOCASE THEN 0 " +
                        "WHEN trim(projectid)=trim(?) COLLATE NOCASE THEN 1 " +
                        "WHEN code LIKE ? COLLATE NOCASE THEN 2 " +
                        "WHEN projectid LIKE ? COLLATE NOCASE THEN 3 " +
                        "WHEN coda LIKE ? COLLATE NOCASE THEN 4 " +
                        "WHEN beneficiary LIKE ? COLLATE NOCASE THEN 5 ELSE 6 END, " +
                        "timestamp DESC, code COLLATE NOCASE ASC LIMIT ?";

                List<String> args = new ArrayList<>();
                args.add(like); args.add(like); args.add(like); args.add(like);
                if (!requiredProjectType.isEmpty()) args.add(requiredProjectType);
                args.add(q); args.add(q); args.add(prefix); args.add(prefix);
                args.add(prefix); args.add(prefix); args.add(String.valueOf(limit));
                c = db.rawQuery(sql, args.toArray(new String[0]));
            }

            while (c != null && c.moveToNext()) {
                ProjectHit hit = new ProjectHit();
                hit.projectId = value(c, 0);
                hit.code = value(c, 1);
                hit.name = value(c, 2);
                hit.beneficiary = value(c, 3);
                hit.projectType = value(c, 4);
                result.add(hit);
            }
        } catch (Exception ignored) {
            // Suggestions are convenience-only; manual code/paste/QR still work.
        } finally {
            if (c != null) c.close();
            db.close();
        }
        return result;
    }

    private final class ProjectHitAdapter extends BaseAdapter {
        private final List<ProjectHit> items;

        ProjectHitAdapter(List<ProjectHit> items) {
            this.items = items == null ? new ArrayList<>() : items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public ProjectHit getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView primary;
            TextView secondary;

            if (convertView instanceof LinearLayout && convertView.getTag() instanceof TextView[]) {
                row = (LinearLayout) convertView;
                TextView[] views = (TextView[]) row.getTag();
                primary = views[0];
                secondary = views[1];
            } else {
                row = new LinearLayout(getContext());
                row.setOrientation(VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.setMinimumHeight(dp(54));

                primary = new TextView(getContext());
                primary.setTextSize(12f);
                primary.setSingleLine(true);
                primary.setEllipsize(TextUtils.TruncateAt.END);
                primary.setTypeface(android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD);

                secondary = new TextView(getContext());
                secondary.setTextSize(10f);
                secondary.setSingleLine(true);
                secondary.setEllipsize(TextUtils.TruncateAt.END);
                secondary.setAlpha(0.72f);

                row.addView(primary, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                LayoutParams secondaryLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                secondaryLp.topMargin = dp(2);
                row.addView(secondary, secondaryLp);
                row.setTag(new TextView[]{primary, secondary});
            }

            ProjectHit hit = getItem(position);
            String code = clean(hit.code);
            String siteId = clean(hit.projectId);
            String name = clean(hit.name);
            String beneficiary = clean(hit.beneficiary);

            String shortSite = compactSiteId(siteId);
            primary.setText("CODE: " + (code.isEmpty() ? "—" : code)
                    + "   •   SITE: " + (shortSite.isEmpty() ? "—" : shortSite));

            String detail = !beneficiary.isEmpty() ? beneficiary : name;
            if (!beneficiary.isEmpty() && !name.isEmpty()
                    && !beneficiary.equalsIgnoreCase(name)) {
                detail = beneficiary + "  •  " + name;
            }
            secondary.setText(detail.isEmpty() ? "Synced project" : detail);
            return row;
        }
    }

    private static String compactSiteId(String siteId) {
        String id = clean(siteId);
        if (id.length() <= 14) return id;
        return id.substring(0, 6) + "…" + id.substring(id.length() - 5);
    }

    private static String normalizeType(String value) {
        String type = clean(value).toUpperCase(Locale.US);
        if (CameraPrefs.DOC_PROJECT_ACTIVITY.equals(type)) return CameraPrefs.DOC_PROJECT_ACTIVITY;
        if (CameraPrefs.DOC_INFRA.equals(type)) return CameraPrefs.DOC_INFRA;
        return type;
    }

    private static String value(Cursor c, int index) {
        if (c == null || c.isNull(index)) return "";
        return clean(c.getString(index));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ProjectHit {
        String projectId;
        String code;
        String name;
        String beneficiary;
        String projectType;
    }
}
