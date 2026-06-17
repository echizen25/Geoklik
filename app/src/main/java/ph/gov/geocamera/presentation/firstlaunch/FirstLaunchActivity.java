package ph.gov.geocamera.presentation.firstlaunch;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

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

    private EditText etFname, etMname, etLname, etBdate, etDesignation;
    private Spinner spGender, spProject;

    private final Calendar bdateCal = Calendar.getInstance();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_first_launch);

        userRepo = new UserRepository(this);

        // If already registered, skip
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

        spGender = findViewById(R.id.spGender);
        spProject = findViewById(R.id.spProject);

        MaterialButton btnSave = findViewById(R.id.btnSave);

        // Gender dropdown
        spGender.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Male", "Female"}
        ));

        // Project dropdown (library)
        spProject.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"RCEF", "CTF", "PHilMech"}
        ));

        // Birthdate picker
        etBdate.setFocusable(false);
        etBdate.setOnClickListener(v -> showBdatePicker());

        btnSave.setOnClickListener(v -> saveUser());
    }

    private void showBdatePicker() {
        Calendar now = Calendar.getInstance();
        DatePickerDialog dlg = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    bdateCal.set(Calendar.YEAR, year);
                    bdateCal.set(Calendar.MONTH, month);
                    bdateCal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    etBdate.setText(dateFmt.format(bdateCal.getTime()));
                },
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH)
        );
        dlg.show();
    }

    private void saveUser() {
        String fname = safe(etFname.getText().toString());
        String mname = safe(etMname.getText().toString());
        String lname = safe(etLname.getText().toString());
        String gender = spGender.getSelectedItem().toString();
        String bdate = safe(etBdate.getText().toString());
        String designation = safe(etDesignation.getText().toString());
        String project = spProject.getSelectedItem().toString();

        if (fname.isEmpty() || lname.isEmpty() || bdate.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content),
                    "Please fill in First name, Last name, and Birthdate.",
                    Snackbar.LENGTH_SHORT).show();
            return;
        }

        // DeviceId (your provider: ANDROID_ID + saved UUID fallback)
        String deviceId = DeviceIdProvider.getOrCreateDeviceId(this);

        // android_id (optional column)
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // uuid (optional column) - can be any stable random; for now generate once per install
        // If you already store a UUID in DeviceIdProvider, you can reuse that.
        String uuid = UUID.randomUUID().toString();

        // userid = firstletter(fname)+firstletter(mname)+lname+deviceId
        String f1 = fname.substring(0, 1).toUpperCase(Locale.US);
        String m1 = mname.isEmpty() ? "X" : mname.substring(0, 1).toUpperCase(Locale.US);
        String ln = lname.toUpperCase(Locale.US).replaceAll("\\s+", "");
        String userid = f1 + m1 + ln + "_" + deviceId;

        // ✅ Call repository (NO ContentValues)
        long rowId = userRepo.insertUser(
                userid,
                fname,
                mname,
                lname,
                gender,
                bdate,          // yyyy-MM-dd
                designation,
                project,
                deviceId,       // saved to imei column (your choice)
                androidId,
                uuid
        );

        if (rowId > 0) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
        } else {
            Snackbar.make(findViewById(android.R.id.content),
                    "Failed to save user. Please try again.",
                    Snackbar.LENGTH_SHORT).show();
        }
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }
}