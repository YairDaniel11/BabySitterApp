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
import android.widget.TextView;
import android.widget.Toast;

/**
 * מסך ההגדרות הראשי.
 * מכשיר זה משמש כ"קלט" (נשאר בחדר הילדים).
 * המכשיר השני (של ההורה) לא צריך אפליקציה בכלל - הוא רק מקבל שיחה רגילה.
 *
 * המסך בנוי לניווט עם מקשים פיזיים (D-pad/חצים) ולא מסך מגע:
 * שדה טקסט -> RadioGroup לבחירת רגישות -> כפתור התחל/עצור, בסדר הזה.
 *
 * מרחיב Activity רגיל (לא AppCompatActivity) - בבדיקת שתי אפליקציות אמיתיות
 * שעובדות על מכשירי שיאומי מקשים, אף אחת מהן לא השתמשה ב-AndroidX/AppCompat.
 */
public class MainActivity extends Activity {

    public static final String PREFS_NAME = "babysitter_prefs";
    public static final String KEY_PHONE = "phone_number";
    public static final String KEY_PHONE_SECONDARY = "phone_number_secondary";
    public static final String KEY_SENSITIVITY = "sensitivity";
    public static final String KEY_RUNNING = "is_running";

    public static final String KEY_AUTO_ANSWER_ENABLED = "auto_answer_enabled";
    public static final String KEY_AUTO_ANSWER_NUMBERS = "auto_answer_numbers";
    public static final String KEY_AUTO_ANSWER_SPEAKER = "auto_answer_speaker";
    public static final String KEY_ALL_CALLS_SPEAKER = "all_calls_speaker";
    public static final String KEY_MUTE_INCOMING_VOICE = "mute_incoming_voice";
    public static final String KEY_MUTE_RINGER_WHILE_ACTIVE = "mute_ringer_while_active";

    // ערכי רגישות (0=הכי רגיש, 100=הכי פחות רגיש) - תואם לנוסחת הסף בשירות
    private static final int SENSITIVITY_HIGH = 0;
    private static final int SENSITIVITY_MEDIUM = 40;
    private static final int SENSITIVITY_LOW = 80;

    // קודי בקשה לבורר אנשי הקשר - מזהים לאיזה שדה להחזיר את המספר שנבחר
    private static final int REQUEST_PICK_CONTACT_PRIMARY = 1001;
    private static final int REQUEST_PICK_CONTACT_SECONDARY = 1002;
    private static final int REQUEST_PICK_CONTACT_AUTO_ANSWER = 1003;

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
    private Button startStopButton;
    private TextView statusText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

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
        startStopButton = findViewById(R.id.startStopButton);
        statusText = findViewById(R.id.statusText);

        phoneNumberInput.setText(prefs.getString(KEY_PHONE, ""));
        secondaryPhoneNumberInput.setText(prefs.getString(KEY_PHONE_SECONDARY, ""));
        autoAnswerNumbersInput.setText(prefs.getString(KEY_AUTO_ANSWER_NUMBERS, ""));
        autoAnswerEnabledCheck.setChecked(prefs.getBoolean(KEY_AUTO_ANSWER_ENABLED, false));
        autoAnswerSpeakerCheck.setChecked(prefs.getBoolean(KEY_AUTO_ANSWER_SPEAKER, true));
        allCallsSpeakerCheck.setChecked(prefs.getBoolean(KEY_ALL_CALLS_SPEAKER, false));
        muteIncomingVoiceCheck.setChecked(prefs.getBoolean(KEY_MUTE_INCOMING_VOICE, false));
        muteRingerWhileActiveCheck.setChecked(prefs.getBoolean(KEY_MUTE_RINGER_WHILE_ACTIVE, false));
        setSensitivitySelection(prefs.getInt(KEY_SENSITIVITY, SENSITIVITY_MEDIUM));

        updateUiState(prefs.getBoolean(KEY_RUNNING, false));

        startStopButton.setOnClickListener(v -> {
            boolean running = prefs.getBoolean(KEY_RUNNING, false);
            if (running) {
                stopListening();
            } else {
                startListening();
            }
        });

        phoneContactButton.setOnClickListener(v -> openContactPicker(REQUEST_PICK_CONTACT_PRIMARY));
        secondaryContactButton.setOnClickListener(v -> openContactPicker(REQUEST_PICK_CONTACT_SECONDARY));
        autoAnswerContactButton.setOnClickListener(v -> openContactPicker(REQUEST_PICK_CONTACT_AUTO_ANSWER));

        // התחלת המיקוד בשדה הטלפון, כדי שניווט עם מקשים יתחיל מקום הגיוני
        phoneNumberInput.requestFocus();
    }

    /**
     * פותח את בורר אנשי הקשר המובנה של המכשיר (Intent.ACTION_PICK על
     * ContactsContract.CommonDataKinds.Phone), כדי לבחור מספר טלפון בלי
     * הקלדה ידנית. לא דורש הרשאת READ_CONTACTS משלנו: הבורר הוא אפליקציה
     * חיצונית (אנשי הקשר של המערכת) שמעניקה גישת קריאה זמנית ל-Uri שהוא
     * מחזיר, בדיוק לצורך הזה.
     */
    private void openContactPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            // אין אפליקציית אנשי קשר שיכולה לטפל בבקשה הזו (נדיר, אבל ייתכן
            // במכשירים מותאמים/מוזלים) - פשוט לא קורה כלום, המשתמש עדיין
            // יכול להקליד את המספר ידנית.
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

    /** שדה "מספרים למענה אוטומטי" הוא רשימה - מוסיפים למה שכבר יש שם, מופרד בפסיק. */
    private void appendAutoAnswerNumber(String number) {
        String existing = autoAnswerNumbersInput.getText().toString().trim();
        if (existing.isEmpty()) {
            autoAnswerNumbersInput.setText(number);
        } else if (!existing.contains(number)) {
            autoAnswerNumbersInput.setText(existing + "," + number);
        }
        autoAnswerNumbersInput.setSelection(autoAnswerNumbersInput.getText().length());
    }

    /** קורא את מספר הטלפון בפועל מתוך ה-Uri שבורר אנשי הקשר מחזיר. */
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
            // תיאורטית לא אמור לקרות (ה-Uri מגיע עם הרשאת קריאה זמנית), אבל
            // ליתר ביטחון לא מפילים את האפליקציה אם מכשיר כלשהו מתנהג אחרת.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * מקש "חזרה" (Back) במכשיר שיאומי מקשים משמש גם למחיקת תו וגם ליציאה
     * מהאפליקציה, תלוי הקשר. בעת עמידה באחד משדות הזנת המספרים - אנחנו
     * "תופסים" את האירוע ומוחקים תו אחד במקום לתת למערכת לסגור את המסך.
     * בכל מקום אחר במסך מתקיים ההתנהגות הרגילה (חזרה/יציאה).
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            EditText focusedNumberField = getFocusedNumberField();
            if (focusedNumberField != null) {
                CharSequence text = focusedNumberField.getText();
                if (text != null && text.length() > 0) {
                    focusedNumberField.getText().delete(text.length() - 1, text.length());
                }
                return true; // האירוע נתפס - לא יוצאים מהאפליקציה
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
        if (savedValue <= SENSITIVITY_HIGH) {
            sensitivityGroup.check(R.id.sensitivityHigh);
        } else if (savedValue >= SENSITIVITY_LOW) {
            sensitivityGroup.check(R.id.sensitivityLow);
        } else {
            sensitivityGroup.check(R.id.sensitivityMedium);
        }
    }

    private int getSelectedSensitivity() {
        int checkedId = sensitivityGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.sensitivityHigh) return SENSITIVITY_HIGH;
        if (checkedId == R.id.sensitivityLow) return SENSITIVITY_LOW;
        return SENSITIVITY_MEDIUM;
    }

    private void startListening() {
        String phone = phoneNumberInput.getText().toString().trim();
        if (phone.isEmpty()) {
            phoneNumberInput.setError("יש להזין מספר טלפון");
            phoneNumberInput.requestFocus();
            return;
        }

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_PHONE, phone);
        editor.putString(KEY_PHONE_SECONDARY, secondaryPhoneNumberInput.getText().toString().trim());
        editor.putString(KEY_AUTO_ANSWER_NUMBERS, autoAnswerNumbersInput.getText().toString().trim());
        editor.putBoolean(KEY_AUTO_ANSWER_ENABLED, autoAnswerEnabledCheck.isChecked());
        editor.putBoolean(KEY_AUTO_ANSWER_SPEAKER, autoAnswerSpeakerCheck.isChecked());
        editor.putBoolean(KEY_ALL_CALLS_SPEAKER, allCallsSpeakerCheck.isChecked());
        editor.putBoolean(KEY_MUTE_INCOMING_VOICE, muteIncomingVoiceCheck.isChecked());
        editor.putBoolean(KEY_MUTE_RINGER_WHILE_ACTIVE, muteRingerWhileActiveCheck.isChecked());
        editor.putInt(KEY_SENSITIVITY, getSelectedSensitivity());
        editor.putBoolean(KEY_RUNNING, true);
        editor.apply();

        Intent serviceIntent = new Intent(this, CryDetectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        updateUiState(true);
    }

    private void stopListening() {
        prefs.edit().putBoolean(KEY_RUNNING, false).apply();
        stopService(new Intent(this, CryDetectionService.class));
        updateUiState(false);
    }

    private void updateUiState(boolean running) {
        if (running) {
            statusText.setText(R.string.status_listening);
            startStopButton.setText(R.string.stop_listening);
            setEntryFieldsEnabled(false);
        } else {
            statusText.setText(R.string.status_idle);
            startStopButton.setText(R.string.start_listening);
            setEntryFieldsEnabled(true);
        }
    }

    private void setEntryFieldsEnabled(boolean enabled) {
        phoneNumberInput.setEnabled(enabled);
        secondaryPhoneNumberInput.setEnabled(enabled);
        autoAnswerNumbersInput.setEnabled(enabled);
        phoneContactButton.setEnabled(enabled);
        secondaryContactButton.setEnabled(enabled);
        autoAnswerContactButton.setEnabled(enabled);
        autoAnswerEnabledCheck.setEnabled(enabled);
        autoAnswerSpeakerCheck.setEnabled(enabled);
        allCallsSpeakerCheck.setEnabled(enabled);
        muteIncomingVoiceCheck.setEnabled(enabled);
        muteRingerWhileActiveCheck.setEnabled(enabled);
        setRadioGroupEnabled(enabled);
    }

    private void setRadioGroupEnabled(boolean enabled) {
        for (int i = 0; i < sensitivityGroup.getChildCount(); i++) {
            sensitivityGroup.getChildAt(i).setEnabled(enabled);
        }
    }
}
