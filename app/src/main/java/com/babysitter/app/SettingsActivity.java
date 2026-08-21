package com.babysitter.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

/**
 * מסך ההגדרות (סעיף א'): כל שדות הקלט שהיו בעבר במסך הבית - מספרי טלפון,
 * מענה אוטומטי (כולל אפשרות להפעיל אותו גם בלי האזנה פעילה - סעיף ב'),
 * הגדרות שמע, רגישות, ובחירת ערכת נושא (מערכת/בהיר/כהה - סעיף ד').
 *
 * המסך בנוי לניווט עם מקשים פיזיים (D-pad/חצים) ולא מסך מגע - בדיוק כמו
 * מסך הבית המקורי; מקש "חזרה" (Back) בזמן שדה מספר בפוקוס מוחק תו אחרון
 * במקום לצאת מהמסך (הועבר לכאן מ-MainActivity הישן).
 */
public class SettingsActivity extends Activity {

    private static final int REQUEST_PICK_CONTACT_PRIMARY = 1001;
    private static final int REQUEST_PICK_CONTACT_SECONDARY = 1002;
    private static final int REQUEST_PICK_CONTACT_AUTO_ANSWER = 1003;
    private static final int PERMISSION_REQUEST_AUTO_ANSWER = 2002;

    private EditText phoneNumberInput;
    private EditText secondaryPhoneNumberInput;
    private EditText autoAnswerNumbersInput;
    private Button phoneContactButton;
    private Button secondaryContactButton;
    private Button autoAnswerContactButton;
    private CheckBox autoAnswerEnabledCheck;
    private CheckBox autoAnswerSpeakerCheck;
    private CheckBox allCallsSpeakerCheck;
    private CheckBox muteIncomingVoiceCheck;
    private CheckBox muteRingerWhileActiveCheck;
    private RadioGroup sensitivityGroup;
    private RadioGroup themeGroup;
    private Button saveButton;
    private SharedPreferences prefs;

    // שומר את הבחירה האחרונה שנעשתה בצ'קבוקס "מענה אוטומטי" *לפני* שבדקנו
    // הרשאות, כדי שאם ההרשאה תיענה בשלילה נוכל להחזיר את הצ'קבוקס למצב
    // הקודם שלו (לא מאושר) במקום להשאיר אותו מסומן בטעות.
    private boolean autoAnswerCheckedPendingPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.applyTheme(this);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE);

        phoneNumberInput = findViewById(R.id.phoneNumberInput);
        secondaryPhoneNumberInput = findViewById(R.id.secondaryPhoneNumberInput);
        autoAnswerNumbersInput = findViewById(R.id.autoAnswerNumbersInput);
        phoneContactButton = findViewById(R.id.phoneContactButton);
        secondaryContactButton = findViewById(R.id.secondaryContactButton);
        autoAnswerContactButton = findViewById(R.id.autoAnswerContactButton);
        autoAnswerEnabledCheck = findViewById(R.id.autoAnswerEnabledCheck);
        autoAnswerSpeakerCheck = findViewById(R.id.autoAnswerSpeakerCheck);
        allCallsSpeakerCheck = findViewById(R.id.allCallsSpeakerCheck);
        muteIncomingVoiceCheck = findViewById(R.id.muteIncomingVoiceCheck);
        muteRingerWhileActiveCheck = findViewById(R.id.muteRingerWhileActiveCheck);
        sensitivityGroup = findViewById(R.id.sensitivityGroup);
        themeGroup = findViewById(R.id.themeGroup);
        saveButton = findViewById(R.id.saveButton);

        loadFromPrefs();

        saveButton.setOnClickListener(v -> {
            saveToPrefs();
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
            finish();
        });

        phoneContactButton.setOnClickListener(v -> openContactPicker(REQUEST_PICK_CONTACT_PRIMARY));
        secondaryContactButton.setOnClickListener(v -> openContactPicker(REQUEST_PICK_CONTACT_SECONDARY));
        autoAnswerContactButton.setOnClickListener(v -> openContactPicker(REQUEST_PICK_CONTACT_AUTO_ANSWER));

        // *** סעיף ב' + ה' ***
        // מענה אוטומטי צריך הרשאות (READ_PHONE_STATE, READ_CALL_LOG,
        // ANSWER_PHONE_CALLS) בלי קשר לכך שההאזנה לבכי כלל לא רצה - לכן
        // מבקשים אותן כאן, ברגע שמנסים לסמן את הצ'קבוקס, ולא מסתמכים על
        // כך שהן התבקשו במסגרת "התחל האזנה" (שאולי מעולם לא הופעלה).
        autoAnswerEnabledCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) return;
            String[] missing = PermissionUtil.missing(this, PermissionUtil.forAutoAnswer());
            if (missing.length == 0) return;
            autoAnswerCheckedPendingPermission = true;
            Toast.makeText(this, R.string.permission_auto_answer_missing, Toast.LENGTH_SHORT).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(missing, PERMISSION_REQUEST_AUTO_ANSWER);
            }
        });

        phoneNumberInput.requestFocus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_AUTO_ANSWER) return;
        if (!autoAnswerCheckedPendingPermission) return;
        autoAnswerCheckedPendingPermission = false;

        if (!PermissionUtil.hasAll(this, PermissionUtil.forAutoAnswer())) {
            // ההרשאה נדחתה - מבטלים את הסימון כדי לא לשמור הגדרה שלא באמת תעבוד.
            autoAnswerEnabledCheck.setChecked(false);
            Toast.makeText(this, R.string.permission_missing_message, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // שומרים אוטומטית גם ביציאה לא-דרך כפתור "שמור" (חזרה/מעבר אפליקציה) -
        // כדי שהגדרות שהוזנו לא "יאבדו" בטעות.
        saveToPrefs();
    }

    private void loadFromPrefs() {
        phoneNumberInput.setText(prefs.getString(AppPrefs.KEY_PHONE, ""));
        secondaryPhoneNumberInput.setText(prefs.getString(AppPrefs.KEY_PHONE_SECONDARY, ""));
        autoAnswerNumbersInput.setText(prefs.getString(AppPrefs.KEY_AUTO_ANSWER_NUMBERS, ""));
        autoAnswerEnabledCheck.setChecked(prefs.getBoolean(AppPrefs.KEY_AUTO_ANSWER_ENABLED, false));
        autoAnswerSpeakerCheck.setChecked(prefs.getBoolean(AppPrefs.KEY_AUTO_ANSWER_SPEAKER, true));
        allCallsSpeakerCheck.setChecked(prefs.getBoolean(AppPrefs.KEY_ALL_CALLS_SPEAKER, false));
        muteIncomingVoiceCheck.setChecked(prefs.getBoolean(AppPrefs.KEY_MUTE_INCOMING_VOICE, false));
        muteRingerWhileActiveCheck.setChecked(prefs.getBoolean(AppPrefs.KEY_MUTE_RINGER_WHILE_ACTIVE, false));
        setSensitivitySelection(prefs.getInt(AppPrefs.KEY_SENSITIVITY, AppPrefs.SENSITIVITY_MEDIUM));
        setThemeSelection(prefs.getString(AppPrefs.KEY_THEME_MODE, AppPrefs.THEME_SYSTEM));
    }

    private void saveToPrefs() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(AppPrefs.KEY_PHONE, phoneNumberInput.getText().toString().trim());
        editor.putString(AppPrefs.KEY_PHONE_SECONDARY, secondaryPhoneNumberInput.getText().toString().trim());
        editor.putString(AppPrefs.KEY_AUTO_ANSWER_NUMBERS, autoAnswerNumbersInput.getText().toString().trim());
        editor.putBoolean(AppPrefs.KEY_AUTO_ANSWER_ENABLED, autoAnswerEnabledCheck.isChecked());
        editor.putBoolean(AppPrefs.KEY_AUTO_ANSWER_SPEAKER, autoAnswerSpeakerCheck.isChecked());
        editor.putBoolean(AppPrefs.KEY_ALL_CALLS_SPEAKER, allCallsSpeakerCheck.isChecked());
        editor.putBoolean(AppPrefs.KEY_MUTE_INCOMING_VOICE, muteIncomingVoiceCheck.isChecked());
        editor.putBoolean(AppPrefs.KEY_MUTE_RINGER_WHILE_ACTIVE, muteRingerWhileActiveCheck.isChecked());
        editor.putInt(AppPrefs.KEY_SENSITIVITY, getSelectedSensitivity());
        editor.putString(AppPrefs.KEY_THEME_MODE, getSelectedTheme());
        editor.apply();
    }

    private void openContactPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Toast.makeText(this, R.string.contact_pick_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        String number = readPhoneNumberFromContactUri(data.getData());
        if (number == null || number.trim().isEmpty()) {
            Toast.makeText(this, R.string.contact_pick_error, Toast.LENGTH_SHORT).show();
            return;
        }
        number = number.trim();

        switch (requestCode) {
            case REQUEST_PICK_CONTACT_PRIMARY:
                phoneNumberInput.setText(number);
                phoneNumberInput.setSelection(phoneNumberInput.getText().length());
                break;
            case REQUEST_PICK_CONTACT_SECONDARY:
                secondaryPhoneNumberInput.setText(number);
                secondaryPhoneNumberInput.setSelection(secondaryPhoneNumberInput.getText().length());
                break;
            case REQUEST_PICK_CONTACT_AUTO_ANSWER:
                appendAutoAnswerNumber(number);
                break;
            default:
                break;
        }
    }

    private void appendAutoAnswerNumber(String number) {
        String existing = autoAnswerNumbersInput.getText().toString().trim();
        if (existing.isEmpty()) {
            autoAnswerNumbersInput.setText(number);
        } else if (!existing.contains(number)) {
            autoAnswerNumbersInput.setText(existing + "," + number);
        }
        autoAnswerNumbersInput.setSelection(autoAnswerNumbersInput.getText().length());
    }

    private String readPhoneNumberFromContactUri(Uri contactDataUri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(contactDataUri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                if (numberIndex >= 0) {
                    return cursor.getString(numberIndex);
                }
            }
        } catch (SecurityException se) {
            // תיאורטית לא אמור לקרות, אבל ליתר ביטחון לא מפילים את האפליקציה.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            EditText focusedNumberField = getFocusedNumberField();
            if (focusedNumberField != null) {
                CharSequence text = focusedNumberField.getText();
                if (text != null && text.length() > 0) {
                    focusedNumberField.getText().delete(text.length() - 1, text.length());
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private EditText getFocusedNumberField() {
        if (phoneNumberInput.hasFocus()) return phoneNumberInput;
        if (secondaryPhoneNumberInput.hasFocus()) return secondaryPhoneNumberInput;
        if (autoAnswerNumbersInput.hasFocus()) return autoAnswerNumbersInput;
        return null;
    }

    private void setSensitivitySelection(int savedValue) {
        if (savedValue <= AppPrefs.SENSITIVITY_HIGH) {
            sensitivityGroup.check(R.id.sensitivityHigh);
        } else if (savedValue >= AppPrefs.SENSITIVITY_LOW) {
            sensitivityGroup.check(R.id.sensitivityLow);
        } else {
            sensitivityGroup.check(R.id.sensitivityMedium);
        }
    }

    private int getSelectedSensitivity() {
        int checkedId = sensitivityGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.sensitivityHigh) return AppPrefs.SENSITIVITY_HIGH;
        if (checkedId == R.id.sensitivityLow) return AppPrefs.SENSITIVITY_LOW;
        return AppPrefs.SENSITIVITY_MEDIUM;
    }

    private void setThemeSelection(String mode) {
        if (AppPrefs.THEME_LIGHT.equals(mode)) {
            themeGroup.check(R.id.themeLight);
        } else if (AppPrefs.THEME_DARK.equals(mode)) {
            themeGroup.check(R.id.themeDark);
        } else {
            themeGroup.check(R.id.themeSystem);
        }
    }

    private String getSelectedTheme() {
        int checkedId = themeGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.themeLight) return AppPrefs.THEME_LIGHT;
        if (checkedId == R.id.themeDark) return AppPrefs.THEME_DARK;
        return AppPrefs.THEME_SYSTEM;
    }
}
