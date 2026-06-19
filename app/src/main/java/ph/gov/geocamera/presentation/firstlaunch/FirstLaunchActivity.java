package ph.gov.geocamera.presentation.firstlaunch;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import android.view.MotionEvent;
import android.widget.ArrayAdapter;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;

import ph.gov.geocamera.R;
import ph.gov.geocamera.core.utils.DeviceIdProvider;
import ph.gov.geocamera.data.repository.UserRepository;
import ph.gov.geocamera.presentation.home.HomeActivity;

public class FirstLaunchActivity extends AppCompatActivity {

    private UserRepository userRepo;

    private TextInputEditText etFname, etMname, etLname, etBdate, etDesignation;
    private MaterialAutoCompleteTextView actGender, actProject;

    private final Calendar bdateCal = Calendar.getInstance();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private static final String[] GENDER_OPTIONS = new String[]{"Male", "Female"};
    private static final String[] PROJECT_OPTIONS = new String[]{"RCEF", "CTF", "PHilMech"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_first_launch);

        userRepo = new UserRepository(this);

        if (userRepo.hasUser()) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }

        etFname = findViewById(R.id.etFname);
        etMname = findViewById(R.id.etMname);
        etLname = findViewById(R.id.etLname);
        etBdate = findViewById(R.id.etBdate);
        etDesignation = findViewById(R.id.etDesignation);

        actGender = findViewById(R.id.actGender);
        actProject = findViewById(R.id.actProject);

        MaterialButton btnSave = findViewById(R.id.btnSave);

        // Middle initial only
        etMname.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1)});

        setupDropdowns();

        etBdate.setFocusable(false);
        etBdate.setOnClickListener(v -> showBdatePicker());

        btnSave.setOnClickListener(v -> saveUser());
    }


    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (getCurrentFocus() != null) {
                hideKeyboard();
                getCurrentFocus().clearFocus();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void setupDropdowns() {
        ArrayAdapter<String> genderAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                GENDER_OPTIONS
        );

        ArrayAdapter<String> projectAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                PROJECT_OPTIONS
        );

        actGender.setAdapter(genderAdapter);
        actProject.setAdapter(projectAdapter);

        actGender.setText(GENDER_OPTIONS[0], false);
        actProject.setText(PROJECT_OPTIONS[0], false);

        actGender.setOnClickListener(v -> actGender.showDropDown());
        actProject.setOnClickListener(v -> actProject.showDropDown());
    }

    private void showBdatePicker() {
        Calendar defaultDate = Calendar.getInstance();
        defaultDate.add(Calendar.YEAR, -18);

        DatePickerDialog dlg = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    bdateCal.set(Calendar.YEAR, year);
                    bdateCal.set(Calendar.MONTH, month);
                    bdateCal.set(Calendar.DAY_OF_MONTH, dayOfMonth);

                    etBdate.setText(dateFmt.format(bdateCal.getTime()));
                },
                defaultDate.get(Calendar.YEAR),
                defaultDate.get(Calendar.MONTH),
                defaultDate.get(Calendar.DAY_OF_MONTH)
        );

        Calendar maxDate = Calendar.getInstance();
        maxDate.add(Calendar.YEAR, -18);
        dlg.getDatePicker().setMaxDate(maxDate.getTimeInMillis());

        dlg.show();
    }

    private void saveUser() {
        String fname = safe(etFname.getText() == null ? "" : etFname.getText().toString());
        String mname = safe(etMname.getText() == null ? "" : etMname.getText().toString());
        String lname = safe(etLname.getText() == null ? "" : etLname.getText().toString());
        String gender = safe(actGender.getText() == null ? "" : actGender.getText().toString());
        String bdate = safe(etBdate.getText() == null ? "" : etBdate.getText().toString());
        String designation = safe(etDesignation.getText() == null ? "" : etDesignation.getText().toString());
        String project = safe(actProject.getText() == null ? "" : actProject.getText().toString());

        if (fname.isEmpty() || lname.isEmpty() || bdate.isEmpty()) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    "Please fill in First name, Last name, and Birthdate.",
                    Snackbar.LENGTH_SHORT
            ).show();
            return;
        }

        if (getAge() < 18) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    "GeoKlik registration requires a minimum age of 18 years old.",
                    Snackbar.LENGTH_LONG
            ).show();
            return;
        }

        if (gender.isEmpty()) gender = "Male";
        if (project.isEmpty()) project = "RCEF";

        String deviceId = DeviceIdProvider.getOrCreateDeviceId(this);
        String androidId = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ANDROID_ID
        );

        String uuid = UUID.randomUUID().toString();

        String f1 = fname.substring(0, 1).toUpperCase(Locale.US);
        String m1 = mname.isEmpty() ? "X" : mname.substring(0, 1).toUpperCase(Locale.US);
        String ln = lname.toUpperCase(Locale.US).replaceAll("\\s+", "");
        String userid = f1 + m1 + ln + "_" + deviceId;

        long rowId = userRepo.insertUser(
                userid,
                fname,
                mname,
                lname,
                gender,
                bdate,
                designation,
                project,
                deviceId,
                androidId,
                uuid
        );

        if (rowId > 0) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
        } else {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    "Failed to save user. Please try again.",
                    Snackbar.LENGTH_SHORT
            ).show();
        }
    }

    private int getAge() {
        Calendar today = Calendar.getInstance();

        int age = today.get(Calendar.YEAR) - bdateCal.get(Calendar.YEAR);

        if (today.get(Calendar.DAY_OF_YEAR) < bdateCal.get(Calendar.DAY_OF_YEAR)) {
            age--;
        }

        return age;
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
