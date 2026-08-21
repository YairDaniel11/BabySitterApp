package com.babysitter.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * מסך הבית (סעיף א' בבקשת השיפור): כפתור עגול גדול במרכז המסך להפעלה/
 * כיבוי של ההאזנה, וכפתור "הגדרות" צמוד לתחתית שפותח את SettingsActivity
 * (שם עברו כל שדות הקלט - מספרי טלפון, מענה אוטומטי, רגישות, ערכת נושא).
 *
 * מכשיר זה משמש כ"קלט" (נשאר בחדר הילדים). המכשיר השני (של ההורה) לא
 * צריך אפליקציה בכלל - הוא רק מקבל שיחה רגילה.
 */
public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_LISTENING = 2001;

    private Button startStopButton;
    private Button settingsButton;
    private TextView statusText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.applyTheme(this);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE);

        statusText = findViewById(R.id.statusText);
        startStopButton = findViewById(R.id.startStopButton);
        settingsButton = findViewById(R.id.settingsButton);

        startStopButton.setOnClickListener(v -> {
            boolean running = prefs.getBoolean(AppPrefs.KEY_RUNNING, false);
            if (running) {
                stopListening();
            } else {
                requestPermissionsThenStart();
            }
        });

        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        startStopButton.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // מרעננים את מצב התצוגה בכל חזרה למסך (למשל אחרי חזרה ממסך
        // ההגדרות, או אם השירות עצר את עצמו ברקע עקב אובדן הרשאה -
        // ראו CryDetectionService.stopListeningDueToPermissionLoss).
        updateUiState(prefs.getBoolean(AppPrefs.KEY_RUNNING, false));
    }

    /**
     * *** סעיף ה' ***
     * לפני שמפעילים בפועל את שירות ההאזנה, מבקשים במפורש את כל ההרשאות
     * הדרושות (מיקרופון, שיחה, מצב שיחה וכו') - ברגע הזה עדיין יש מבוגר
     * שלוחץ על הכפתור ויכול לאשר דיאלוג. אם משהו נדחה - ההאזנה *לא*
     * מופעלת בכלל, כדי שלעולם לא ניגיע למצב שבו הילד תלוי במסך חיוג ידני
     * שהוא לא יכול להשתמש בו.
     */
    private void requestPermissionsThenStart() {
        String[] missing = PermissionUtil.missing(this, PermissionUtil.forListening());
        if (missing.length == 0) {
            startListening();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(missing, PERMISSION_REQUEST_LISTENING);
        } else {
            // לא אמור לקרות (מתחת ל-M כל ההרשאות כבר מאושרות בהתקנה),
            // אבל ליתר ביטחון פשוט ננסה להתחיל.
            startListening();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_LISTENING) return;

        if (PermissionUtil.hasAll(this, PermissionUtil.forListening())) {
            startListening();
        } else {
            Toast.makeText(this, R.string.permission_missing_message, Toast.LENGTH_LONG).show();
        }
    }

    private void startListening() {
        String phone = prefs.getString(AppPrefs.KEY_PHONE, "");
        if (AppPrefs.isEmpty(phone)) {
            Toast.makeText(this, R.string.phone_label, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        prefs.edit().putBoolean(AppPrefs.KEY_RUNNING, true).apply();

        Intent serviceIntent = new Intent(this, CryDetectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        updateUiState(true);
    }

    private void stopListening() {
        prefs.edit().putBoolean(AppPrefs.KEY_RUNNING, false).apply();
        stopService(new Intent(this, CryDetectionService.class));
        updateUiState(false);
    }

    private void updateUiState(boolean running) {
        if (running) {
            statusText.setText(R.string.status_listening);
            startStopButton.setText(R.string.stop_listening_short);
            startStopButton.setBackgroundResource(R.drawable.bg_toggle_active);
        } else {
            statusText.setText(R.string.status_idle);
            startStopButton.setText(R.string.start_listening_short);
            startStopButton.setBackgroundResource(R.drawable.bg_toggle_idle);
        }
    }
}
