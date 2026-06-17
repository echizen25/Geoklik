package ph.gov.geocamera.core.utils;

import android.text.TextWatcher;
import android.text.Editable;

public abstract class SimpleTextWatcher implements TextWatcher {
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void afterTextChanged(Editable s) {}
}