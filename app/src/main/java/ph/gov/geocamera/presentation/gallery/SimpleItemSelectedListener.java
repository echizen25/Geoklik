package ph.gov.geocamera.presentation.gallery;

import android.view.View;
import android.widget.AdapterView;

public class SimpleItemSelectedListener implements AdapterView.OnItemSelectedListener {

    public interface OnSelected { void onSelected(int position); }
    private final OnSelected cb;

    public SimpleItemSelectedListener(OnSelected cb) { this.cb = cb; }

    @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (cb != null) cb.onSelected(position);
    }

    @Override public void onNothingSelected(AdapterView<?> parent) {}
}