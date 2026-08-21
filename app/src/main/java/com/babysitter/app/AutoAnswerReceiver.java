package com.babysitter.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;

import java.util.List;

/**
 * *** מענה אוטומטי עצמאי (סעיף ב' בבקשת השיפור) ***
 *
 * receiver רשום ב-manifest (לא נוצר דינמית בתוך שירות) שמאזין לשידור
 * המערכת android.intent.action.PHONE_STATE. זהו שידור "מוגן" (protected
 * broadcast) שנשלח ע"י המערכת עצמה - לכן, בניגוד לרוב השידורים המרומזים
 * (implicit broadcasts) שחסומים החל מאנדרואיד 8 לרכיבים שרשומים רק
 * ב-manifest, receiver סטטי כזה *כן* ממשיך לקבל אותו באופן אמין, גם אם
 * האפליקציה לא רצה כרגע ברקע.
 *
 * זה מה שמאפשר להפריד לחלוטין בין "האזנה לבכי" (CryDetectionService,
 * שמופעל/מכובה ידנית ע"י כפתור ההפעלה במסך הבית) לבין "מענה אוטומטי
 * לשיחות" - אפשר להפעיל את המענה האוטומטי גם כשההאזנה כבויה לגמרי.
 * ה-receiver הזה מגיב אך ורק למצב RINGING, שקיים אך ורק בשיחות *נכנסות* -
 * לעולם לא בשיחות יוצאות (אלה מטופלות רק ע"י CryDetectionService, ורק
 * כשהוא פעיל).
 */
public class AutoAnswerReceiver extends BroadcastReceiver {

    private static final long AUTO_ANSWER_DELAY_MS = 700;
    private static final String KEY_AUDIO_ADJUSTED = "auto_answer_audio_adjusted";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (state == null) return;

        SharedPreferences prefs = context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE);

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            handleRinging(context, intent, prefs);
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            // סיום שיחה - אם המענה האוטומטי הוא זה שהתאים את השמע (רמקול),
            // משחזרים כאן. (משתמשים בדגל שמור ב-prefs ולא בשדה על המחלקה,
            // כי אנדרואיד לא מבטיח שאותו מופע receiver "ישרוד" בין קריאות.)
            if (prefs.getBoolean(KEY_AUDIO_ADJUSTED, false)) {
                AudioRoutingUtil.clearSpeaker(context);
                prefs.edit().putBoolean(KEY_AUDIO_ADJUSTED, false).apply();
            }
        }
        // EXTRA_STATE_OFFHOOK - לא נוגעים כאן בכלל: זה קורה גם בשיחות
        // יוצאות, והטיפול בהן (אם בכלל) הוא באחריות CryDetectionService.
    }

    private void handleRinging(Context context, Intent intent, SharedPreferences prefs) {
        boolean autoAnswerEnabled = prefs.getBoolean(AppPrefs.KEY_AUTO_ANSWER_ENABLED, false);
        if (!autoAnswerEnabled) return;

        String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

        List<String> autoAnswerNumbers = AppPrefs.parseNumberList(
                prefs.getString(AppPrefs.KEY_AUTO_ANSWER_NUMBERS, ""));
        if (autoAnswerNumbers.isEmpty()) {
            // אם לא הוגדרה רשימה ייעודית למענה אוטומטי - נשתמש במספרי ההורים שהוגדרו
            String primary = prefs.getString(AppPrefs.KEY_PHONE, "");
            String secondary = prefs.getString(AppPrefs.KEY_PHONE_SECONDARY, "");
            if (!AppPrefs.isEmpty(primary)) autoAnswerNumbers.add(AppPrefs.normalizeNumber(primary));
            if (!AppPrefs.isEmpty(secondary)) autoAnswerNumbers.add(AppPrefs.normalizeNumber(secondary));
        }
        if (autoAnswerNumbers.isEmpty()) return;

        boolean approved = false;
        for (String candidate : autoAnswerNumbers) {
            if (AppPrefs.numberMatches(incomingNumber, candidate)) {
                approved = true;
                break;
            }
        }
        if (!approved) return;

        boolean speaker = prefs.getBoolean(AppPrefs.KEY_AUTO_ANSWER_SPEAKER, true);

        // goAsync() מאריך את "חלון הריצה" המותר ל-receiver (בפועל עד בערך
        // 10 שניות, שמספיק בהרבה ל-AUTO_ANSWER_DELAY_MS) - בלעדיו, המערכת
        // עלולה להוריד את עדיפות התהליך ברגע ש-onReceive חוזר, לפני
        // שה-Runnable המושהה שמתוזמן כאן מספיק לרוץ בפועל.
        final PendingResult pendingResult = goAsync();
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            try {
                boolean answered = attemptAnswerRingingCall(context);
                if (answered && speaker) {
                    AudioRoutingUtil.applySpeaker(context);
                    prefs.edit().putBoolean(KEY_AUDIO_ADJUSTED, true).apply();
                }
            } finally {
                pendingResult.finish();
            }
        }, AUTO_ANSWER_DELAY_MS);
    }

    private boolean attemptAnswerRingingCall(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // ה-API הרשמי (דורש הרשאת ANSWER_PHONE_CALLS) - זמין מאנדרואיד 8+.
                // חשוב: acceptRingingCall() נמצאת על TelecomManager (ניהול
                // שיחות), לא על TelephonyManager (סטטוס רשת/שיחה).
                TelecomManager telecomManager =
                        (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null) {
                    telecomManager.acceptRingingCall();
                    return true;
                }
                return false;
            } else {
                // מכשירי אנדרואיד ישנים יותר (כמו יעד ה-minSdk 19 של האפליקציה
                // הזו) לא חשופים ל-API הרשמי. השיטה הישנה שהייתה נהוגה אז היא
                // סימולציה של לחיצה על כפתור "הדסט" (media button) - זה עבד
                // באמינות חלקית בלבד ותלוי ביצרן/גרסה, אז זו רק ניסיון best
                // effort ולא הבטחה.
                simulateHeadsetHookAnswer(context);
                return true;
            }
        } catch (SecurityException se) {
            // אין הרשאת ANSWER_PHONE_CALLS - לא ניתן לענות אוטומטית. receiver
            // לא יכול לבקש הרשאה בעצמו - הבקשה המפורשת מתבצעת מראש במסך
            // ההגדרות, לפני שהצ'קבוקס "מענה אוטומטי" בכלל ניתן לסימון
            // (ראו SettingsActivity) - כך שזה אמור לקרות רק אם ההרשאה
            // נשללה ידנית אחר כך מהגדרות המכשיר.
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void simulateHeadsetHookAnswer(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long eventTime = SystemClock.uptimeMillis();
        KeyEvent downEvent = new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0);
        KeyEvent upEvent = new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0);
        am.dispatchMediaKeyEvent(downEvent);
        am.dispatchMediaKeyEvent(upEvent);
    }
}
