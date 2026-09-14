package ph.gov.geocamera.presentation.site;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.CameraPrefs;
import ph.gov.geocamera.data.local.db.GeoDbHelper;

/**
 * Compact Top-5 project helper for Change Project.
 *
 * Suggestions come only from the already-synced local project cache, so this
 * remains useful offline and never adds a network request while the user types.
 * Search matches Project Code, Site ID/projectid, Project Name and Beneficiary.
 */
public class ProjectSuggestionsView extends LinearLayout {

    private static final int LIMIT = 5;

    private TextInputEditText input;
    private LinearLayout rows;
    private TextView heading;
    private GeoDbHelper dbHelper;
    private String requiredProjectType = "";

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

        heading = new TextView(getContext());
        heading.setText("SUGGESTED PROJECTS");
        heading.setTextSize(10f);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setLetterSpacing(0.06f);
        heading.setAlpha(0.72f);
        addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        rows = new LinearLayout(getContext());
        rows.setOrientation(VERTICAL);
        LayoutParams rowsLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowsLp.topMargin = dp(5);
        addView(rows, rowsLp);

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
                render(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Empty query = five most recently synced projects. As the user types,
        // these immediately become the five best local matches.
        render(input.getText() == null ? "" : input.getText().toString());
    }

    private void render(String query) {
        List<ProjectHit> hits = queryLocalProjects(query, LIMIT);
        rows.removeAllViews();

        if (hits.isEmpty()) {
            setVisibility(GONE);
            return;
        }

        heading.setText(clean(query).isEmpty() ? "SUGGESTED PROJECTS" : "TOP MATCHES");
        for (int i = 0; i < hits.size(); i++) {
            final ProjectHit hit = hits.get(i);
            MaterialCardView card = new MaterialCardView(getContext());
            card.setCardElevation(0f);
            card.setRadius(dp(12));
            card.setClickable(true);
            card.setFocusable(true);
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(resolveColor(com.google.android.material.R.attr.colorOutline, 0xFFE0E0E0));
            card.setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF));

            TextView text = new TextView(getContext());
            text.setPadding(dp(11), dp(8), dp(11), dp(8));
            text.setText(buildLabel(hit));
            text.setTextSize(11f);
            text.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, 0xFF1A1A1A));
            text.setMaxLines(4);
            card.addView(text, new MaterialCardView.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            card.setOnClickListener(v -> choose(hit));

            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(5);
            rows.addView(card, lp);
        }
        setVisibility(VISIBLE);
    }

    private CharSequence buildLabel(ProjectHit hit) {
        String code = clean(hit.code);
        String siteId = clean(hit.projectId);
        String name = clean(hit.name);
        String beneficiary = clean(hit.beneficiary);

        StringBuilder out = new StringBuilder();
        out.append("CODE: ").append(code.isEmpty() ? "—" : code);
        out.append("   •   SITE ID: ").append(siteId.isEmpty() ? "—" : siteId);
        if (!name.isEmpty()) out.append('\n').append(name);
        if (!beneficiary.isEmpty() && !beneficiary.equalsIgnoreCase(name)) {
            out.append('\n').append("Beneficiary: ").append(beneficiary);
        }
        return out.toString();
    }

    private void choose(ProjectHit hit) {
        if (input == null) return;
        String value = clean(hit.code);
        if (value.isEmpty()) value = clean(hit.projectId);
        if (value.isEmpty()) return;

        input.setText(value);
        input.setSelection(value.length());
        input.clearFocus();
        // Reuse SetSiteActivity's existing verification/classification path.
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
            // Suggestions are convenience-only; never interfere with manual code/QR entry.
        } finally {
            if (c != null) c.close();
            db.close();
        }
        return result;
    }

    private int resolveColor(int attr, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                try { return androidx.core.content.ContextCompat.getColor(getContext(), value.resourceId); }
                catch (Exception ignored) {}
            }
            return value.data;
        }
        return fallback;
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
