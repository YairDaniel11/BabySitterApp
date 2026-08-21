package com.babysitter.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.media.MediaRecorder;

import java.io.File;

/**
 * שירות רקע שרץ במכשיר שנשאר בחדר הילדים.
 * לא שולח שום דבר לשרת ולא ל-AI חיצוני - כל הבדיקה (עוצמת קול) נעשית
 * מקומית על המכשיר עצמו (MediaRecorder.getMaxAmplitude), ולכן זה עובד
 * לגמרי אופליין. כשהעוצמה עוברת סף מסוים - מתבצעת שיחה רגילה (ACTION_CALL)
 * למספר שהוגדר.
 *
 * מעקב מצב שיחה (TelephonyManager/PhoneStateListener) נוסף כדי:
 * 1. להחזיר את ההאזנה למיקרופון מיד כשכל שיחה (יוצאת/נכנסת, שלנו או לא)
 *    מסתיימת - זה מה שתיקן את הבאג שבו ניתוק שיחה נכנסת "תקע" את ההאזנה
 *    עד הפעלה מחדש ידנית.
 * 2. לזהות "אין מענה" בשיחה היוצאת שלנו ולחייג למספר השני, אם הוגדר.
 *
 * *** שינוי ארכיטקטוני (סעיף ב') ***
 * מענה אוטומטי לשיחות *נכנסות* עבר החוצה מכאן ל-AutoAnswerReceiver -
 * receiver עצמאי שנרשם ב-manifest ופועל בלי קשר לכך שהשירות הזה (האזנה
 * לבכי) פעיל או לא. השירות כאן ממשיך לטפל אך ורק בשיחות *יוצאות* שהוא
 * עצמו יזם (זיהוי "אין מענה" -> ניסיון למספר שני), ובהשתקת/הפעלת הרמקול
 * לשיחות האלה בלבד. ראו AutoAnswerReceiver.java להסבר המלא על ההפרדה.
 */
public class CryDetectionService extends Service {

    private static final String CHANNEL_ID = "babysitter_channel";
    private static final String WARNING_CHANNEL_ID = "babysitter_warning_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int WARNING_NOTIFICATION_ID = 2;

    // כל כמה מילישניות בודקים את עוצמת הקול
    private static final long SAMPLE_INTERVAL_MS = 700;

    // אחרי שיחה - כמה זמן לא להתקשר שוב (כדי לא לחייג שוב ושוב ברצף)
    private static final long CALL_COOLDOWN_MS = 60_000;

    // כל כמה זמן להפעיל מחדש את ההקלטה, כדי שקובץ ההקלטה הזמני לא יתפח
    // לגודל בלתי מוגבל (חשוב במיוחד במכשיר ישן עם מקום אחסון מוגבל, כשהאפליקציה
    // רצה ברצף שעות ארוכות בלילה)
    private static final long RECORDER_RESTART_INTERVAL_MS = 4 * 60_000;

    // אחרי כמה כשלונות רצופים בהכנת ההקלטה, ננסה שוב בהפרש הזה
    private static final long RETRY_INTERVAL_MS = 3_000;

    // כמה זמן לתת לשיחה היוצאת שלנו (למספר הראשון) לפני שנחשיב אותה כ"אין
    // מענה" ונחייג למספר השני. אין ב-Android דרך אמינה להבדיל "נענתה" מ"לא
    // נענתה" בלי להיות אפליקציית החייגן ברירת המחדל - זו הערכה (heuristic)
    // המבוססת על משך הזמן שהטלפון נשאר "מחוץ לכונן" (OFFHOOK). אפשר לכוונן
    // לפי הצורך בפועל.
    private static final long ANSWER_TIMEOUT_MS = 15_000;

    // *** מגבלת פלטפורמה חשובה (קריאה לפני שינוי הקבועים למטה) ***
    // Android לא חושף לאפליקציות רגילות (שאינן אפליקציית החייגן/טלפוניה
    // ברירת המחדל) שום API שמבדיל "ההורה ענה בפועל" מ"השיחה עברה לתא קולי".
    // שני המצבים נראים מבחוץ בדיוק אותו הדבר - מעבר למצב OFFHOOK. אפילו
    // ה"מצלצל" של שיחה יוצאת לא נחשף כמצב נפרד (CALL_STATE_RINGING מיועד
    // רק לשיחות *נכנסות*) - כך שגם ה"אין מענה" הבסיסי הוא כבר הערכה, לא
    // זיהוי ודאי. מה שאפשר לעשות זה לשפר את ההערכה עם איתות נוסף:
    //
    // אם שיחה מגיעה ל-OFFHOOK *מהר מאוד* אחרי שיזמנו אותה (הרבה לפני שהיו
    // מספיק צלצולים כדי שאדם באמת יספיק לענות) - זה חשוד כדחייה מיידית
    // שמנותבת ישר לתא קולי (או מכשיר כבוי/מחוץ לכיסוי שמנותב לתא קולי
    // מהר). זה *לא* הוכחה, רק איתות נוסף - משלבים אותו עם משך ה-OFFHOOK
    // הקיים ("אין מענה" אם קצר מדי בנוסף). זה לא יזהה במדויק 100% מהמקרים
    // (למשל תא קולי שמנותב רק אחרי כמה צלצולים ייראה כמו "נענה"), אבל
    // משפר משמעותית את הזיהוי עבור המקרה הנפוץ של דחייה יזומה.
    private static final long FAST_CONNECT_SUSPICIOUS_MS = 3_000;

    // --- שיפור זיהוי הרעש (סעיף ד' המקורי) ---
    // רעש רגעי בודד (טריקת דלת, שיעול, טלוויזיה) יכול לחצות את הסף לרגע
    // אחד ולגרום לחיוג שווא. דורשים כמה דגימות רצופות מעל הסף (כל דגימה כל
    // SAMPLE_INTERVAL_MS) לפני שמחשיבים את זה כבכי אמיתי ומחייגים - זה
    // דורש רעש *מתמשך* של כ-1.4 שניות ומעלה, שמתאים הרבה יותר לבכי אמיתי
    // מאשר רעש חד-פעמי, בלי לפגוע משמעותית בזמן התגובה.
    private static final int REQUIRED_CONSECUTIVE_SAMPLES = 2;

    // --- תיקון באג "מפסיק להתקשר אחרי חיוג ראשון" ---
    // רואים תיעוד מלא של הבעיה וההסבר לתיקון בהערה מעל checkAmplitude()
    // ובהערה מעל watchdogRunnable. בקצרה: "שומר סף" (watchdog) שרץ כל
    // WATCHDOG_INTERVAL_MS ובודק שדגימת עוצמה תקינה *כלשהי* קרתה לאחרונה;
    // אם לא (וגם אין שיחה פעילה כרגע) - מכריח הפעלה מחדש של ההקלטה, במקום
    // לחכות לטיימר הרענון הכללי של 4 דקות או לתלות הכל בכך ש-callback של
    // שגיאה בהכרח יגיע (לא תמיד קורה בפועל בכל מכשיר/יצרן).
    private static final long WATCHDOG_INTERVAL_MS = 10_000;
    private static final long WATCHDOG_STALE_THRESHOLD_MS = 15_000;

    // כמה זמן אחרי שהשיחה הסתיימה (חזרה ל-IDLE) לחכות לפני שמפעילים מחדש
    // את ההקלטה - נותן למיקרופון רגע להשתחרר בפועל מהמערכת.
    private static final long RESTART_AFTER_CALL_DELAY_MS = 800;

    private MediaRecorder recorder;
    private Handler handler;
    private Runnable sampleRunnable;
    private Runnable restartRunnable;
    private PowerManager.WakeLock wakeLock;

    private String phoneNumber;
    private String secondaryPhoneNumber;
    private int sensitivityPercent; // 0-100 מהמשתמש, 0=רגיש מאוד, 100=רגיש הכי פחות
    private long lastCallTimeMs = 0;

    // כמה דגימות רצופות היו מעל הסף עד כה (מתאפס בכל דגימה שמתחת לסף) -
    // ראו REQUIRED_CONSECUTIVE_SAMPLES.
    private int consecutiveOverThreshold = 0;

    // "שומר סף" (watchdog): מתי בפעם האחרונה התקבלה דגימת עוצמה תקינה
    // (בהצלחה, בלי חריגה) - ראו הסבר מלא ב-WATCHDOG_* והערה מעל
    // watchdogRunnable.
    private long lastSuccessfulSampleMs = 0;
    private Runnable watchdogRunnable;

    // האם יש כרגע שיחה פעילה (מצלצלת/מחוברת) מכל סוג - יוצאת שלנו, נכנסת,
    // או אפילו שיחה שלא קשורה לאפליקציה בכלל (למשל מישהו אחר במכשיר
    // התקשר). כל עוד זה true - לא מפעילים מחדש הקלטה, וה-watchdog לא נוגע
    // בכלום (השיחה עצמה תופסת את המיקרופון, וזה תקין וצפוי).
    private boolean callCurrentlyActive = false;

    // הגדרות שמע לשיחות שהשירות הזה עצמו יזם/מטפל בהן (יוצאות בלבד -
    // מענה אוטומטי לנכנסות עבר ל-AutoAnswerReceiver)
    private boolean allCallsSpeaker;
    private boolean muteIncomingVoice;
    private boolean muteRingerWhileActive;

    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;

    // מצב מעקב אחרי שיחה יוצאת שהאפליקציה עצמה יזמה (כדי לזהות "אין מענה")
    private boolean weInitiatedOutgoingCall = false;
    private boolean triedSecondaryThisCycle = false;
    private long outgoingCallOffHookStartMs = 0;
    private long callInitiatedAtMs = 0;

    // מצב מעקב כללי כדי לדעת אם השיחה הנוכחית (יוצאת שלנו) צריכה טיפול
    // שמע (רמקול/השתקה), ולשחזר בסיום
    private boolean audioAdjustedForCurrentCall = false;
    private int savedVoiceCallVolume = -1;
    private int savedRingerVolume = -1;

    private boolean restartScheduled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE);
        phoneNumber = prefs.getString(AppPrefs.KEY_PHONE, "");
        secondaryPhoneNumber = prefs.getString(AppPrefs.KEY_PHONE_SECONDARY, "");
        sensitivityPercent = prefs.getInt(AppPrefs.KEY_SENSITIVITY, 40);

        allCallsSpeaker = prefs.getBoolean(AppPrefs.KEY_ALL_CALLS_SPEAKER, false);
        muteIncomingVoice = prefs.getBoolean(AppPrefs.KEY_MUTE_INCOMING_VOICE, false);
        muteRingerWhileActive = prefs.getBoolean(AppPrefs.KEY_MUTE_RINGER_WHILE_ACTIVE, false);

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        applyRingerMuteIfNeeded();
        registerPhoneStateListener();
        startRecordingAndMonitoring();
        scheduleRecorderRestart();
        scheduleWatchdog();

        // אם השירות נהרג ע"י המערכת - שיתחיל מחדש עם אותם נתונים (ה-Intent האחרון)
        return START_REDELIVER_INTENT;
    }

    // ---------------------------------------------------------------------
    // מעקב מצב שיחה (תיקון הבאג + "אין מענה" -> מספר שני)
    // ---------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void registerPhoneStateListener() {
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) return;

        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                handleCallStateChanged(state);
            }
        };
        try {
            telephonyManager.listen(phoneStateListener,
                    PhoneStateListener.LISTEN_CALL_STATE);
        } catch (SecurityException se) {
            // אין הרשאת READ_PHONE_STATE מסיבה כלשהי - נמשיך לפעול בלי המעקב
            // הזה (תכונת "אין מענה למספר שני" פשוט לא תפעל, אבל גילוי בכי
            // וחיוג ידני עדיין עובדים כרגיל).
            telephonyManager = null;
        }
    }

    private void unregisterPhoneStateListener() {
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    private void handleCallStateChanged(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                callCurrentlyActive = true;
                // ברגע שיש שיחה מצלצלת, המיקרופון עומד להיתפס בכל מקרה (גם
                // אם AutoAnswerReceiver יענה עליה אוטומטית) - עוצרים את
                // ההקלטה שלנו באופן יזום כאן, במקום להשאיר אותה רצה ולסמוך
                // על כך שהיא "תיכשל בעדינות" כשהשיחה תיקח את המיקרופון
                // בפועל. עצירה יזומה כאן מונעת מהמצב הזה להיווצר מלכתחילה.
                pauseRecordingForCall();
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                callCurrentlyActive = true;
                if (weInitiatedOutgoingCall && outgoingCallOffHookStartMs == 0) {
                    outgoingCallOffHookStartMs = SystemClock.elapsedRealtime();
                    applyInCallAudioSettings();
                }
                pauseRecordingForCall();
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                callCurrentlyActive = false;
                handleCallEnded();
                break;
        }
    }

    /**
     * עוצר את לולאת הדגימה וה-recorder הנוכחיים בלי לגעת בדגלי מעקב השיחה
     * (weInitiatedOutgoingCall וכו') - להבדיל מ-startRecordingAndMonitoring
     * שגם מפעיל recorder חדש מיד. כאן רק "משתיקים" את ההאזנה בזמן שהמיקרופון
     * ממילא הולך להיתפס ע"י השיחה עצמה, כדי לא להשאיר recorder שרץ על עיוור
     * ועלול להיתקע בשקט. ההאזנה תחודש כרגיל מ-handleCallEnded() כשהשיחה
     * תסתיים (state IDLE).
     */
    private void pauseRecordingForCall() {
        if (sampleRunnable != null) {
            handler.removeCallbacks(sampleRunnable);
        }
        stopRecorderQuietly();
    }

    private void handleCallEnded() {
        // "אין מענה" (או דחייה שהועברה לתא קולי) -> נסה מספר שני, רק עבור
        // שיחה שהאפליקציה עצמה יזמה. שני איתותים משולבים כאן (ראו הסבר
        // מפורט ב-FAST_CONNECT_SUSPICIOUS_MS למעלה):
        //  1) משך ה-OFFHOOK קצר מדי (כמו קודם) - כנראה לא נענתה בפועל.
        //  2) המעבר ל-OFFHOOK קרה *מהר מדי* אחרי שיזמנו את השיחה - חשוד
        //     כדחייה מיידית שמנותבת ישר לתא קולי, גם אם אחר כך תא קולי
        //     "מדבר" הרבה זמן ומאריך את משך ה-OFFHOOK.
        // זו עדיין הערכה בלבד - אין ב-Android API רשמי שמבטיח זיהוי ודאי
        // של תא קולי לאפליקציה שהיא לא החייגן ברירת המחדל.
        if (weInitiatedOutgoingCall) {
            long durationMs = outgoingCallOffHookStartMs == 0
                    ? 0
                    : SystemClock.elapsedRealtime() - outgoingCallOffHookStartMs;
            long timeToOffHookMs = outgoingCallOffHookStartMs == 0
                    ? -1
                    : outgoingCallOffHookStartMs - callInitiatedAtMs;

            boolean shortDuration = outgoingCallOffHookStartMs == 0 || durationMs < ANSWER_TIMEOUT_MS;
            boolean fastConnectSuspicious = timeToOffHookMs >= 0 && timeToOffHookMs < FAST_CONNECT_SUSPICIOUS_MS;
            boolean likelyUnanswered = shortDuration || fastConnectSuspicious;

            weInitiatedOutgoingCall = false;
            outgoingCallOffHookStartMs = 0;
            callInitiatedAtMs = 0;

            if (likelyUnanswered && !triedSecondaryThisCycle && !AppPrefs.isEmpty(secondaryPhoneNumber)) {
                triedSecondaryThisCycle = true;
                placeCallTo(secondaryPhoneNumber);
            }
        }

        restoreInCallAudioSettings();

        // תיקון הבאג המרכזי: כל שיחה שמסתיימת (בין אם שלנו, בין אם נכנסת
        // שנענתה/נדחתה) עלולה להשאיר את ה-MediaRecorder במצב לא תקין בלי
        // שיירו callback שגיאה. לכן, בלי קשר למצב ה-recorder, מפעילים מחדש
        // את ההאזנה באופן יזום ברגע שהשיחה מסתיימת - במקום לחכות להפעלה
        // מחדש ידנית או לטיימר הרענון של 4 דקות. startRecordingAndMonitoring()
        // עצמה כבר מנקה כל לולאת דגימה קודמת לפני שהיא מתחילה מחדש.
        handler.postDelayed(this::startRecordingAndMonitoring, RESTART_AFTER_CALL_DELAY_MS);
    }

    // ---------------------------------------------------------------------
    // שמע לשיחות (רמקול / השתקת קול נכנס / השתקת צלצול) - לשיחות היוצאות
    // שהשירות הזה עצמו יזם בלבד (ראו AudioRoutingUtil להסבר על תיקון הרמקול)
    // ---------------------------------------------------------------------

    private void applyRingerMuteIfNeeded() {
        // השתקת הצלצול "כל עוד האזנה פעילה" (ולא רק בזמן שיחה ספציפית)
        // ממומשת ברמת ה-STREAM_RING בעת הפעלת ההאזנה - זה נשאר כפי שהיה.
        if (!muteRingerWhileActive) return;
        android.media.AudioManager am =
                (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            savedRingerVolume = am.getStreamVolume(android.media.AudioManager.STREAM_RING);
            am.setStreamVolume(android.media.AudioManager.STREAM_RING, 0, 0);
        } catch (SecurityException ignored) {
            // בחלק מהמכשירים/גרסאות שינוי עוצמת קול דורש הרשאת "אין הפרעה" -
            // אם זה נכשל, פשוט לא משתיקים את הצלצול, אבל שאר האפליקציה תמשיך לפעול.
        }
    }

    private void restoreRingerVolume() {
        if (savedRingerVolume < 0) return;
        android.media.AudioManager am =
                (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                am.setStreamVolume(android.media.AudioManager.STREAM_RING, savedRingerVolume, 0);
            } catch (SecurityException ignored) {
            }
        }
        savedRingerVolume = -1;
    }

    private void applyInCallAudioSettings() {
        if (audioAdjustedForCurrentCall) return;
        if (allCallsSpeaker) {
            AudioRoutingUtil.applySpeaker(this);
        }
        if (muteIncomingVoice) {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                try {
                    savedVoiceCallVolume = am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL);
                    am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, 0, 0);
                } catch (SecurityException ignored) {
                }
            }
        }
        audioAdjustedForCurrentCall = true;
    }

    private void restoreInCallAudioSettings() {
        if (!audioAdjustedForCurrentCall) return;
        if (allCallsSpeaker) {
            AudioRoutingUtil.clearSpeaker(this);
        }
        if (savedVoiceCallVolume >= 0) {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                try {
                    am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume, 0);
                } catch (SecurityException ignored) {
                }
            }
        }
        savedVoiceCallVolume = -1;
        audioAdjustedForCurrentCall = false;
    }

    // ---------------------------------------------------------------------
    // הפעלת שיחה יוצאת (בכי מזוהה, או ניסיון למספר שני אחרי "אין מענה")
    // ---------------------------------------------------------------------

    private void scheduleRecorderRestart() {
        if (restartRunnable != null) {
            handler.removeCallbacks(restartRunnable);
        }
        restartRunnable = new Runnable() {
            @Override
            public void run() {
                startRecordingAndMonitoring();
                handler.postDelayed(this, RECORDER_RESTART_INTERVAL_MS);
            }
        };
        handler.postDelayed(restartRunnable, RECORDER_RESTART_INTERVAL_MS);
    }

    /**
     * "שומר סף" (watchdog) - התיקון המרכזי לבאג "מפסיק להתקשר אחרי חיוג
     * ראשון, עד כיבוי/הפעלה מחדש - ולפעמים מתאושש מעצמו אחרי כמה דקות".
     *
     * למה זה קרה: ל-checkAmplitude() יש רק שתי דרכים לגלות שההאזנה "מתה" -
     * חריגה (Exception) בקריאה ל-getMaxAmplitude(), או callback שגיאה
     * מה-MediaRecorder עצמו (setOnErrorListener). הבעיה: בחלק מהמכשירים/
     * תרחישים (בעיקר כשהמיקרופון נתפס ע"י שיחה טלפונית פעילה) אף אחד
     * מהשניים לא בהכרח קורה - ה-recorder פשוט ממשיך "לרוץ" בלי לזרוק שגיאה,
     * אבל גם לא מייצר יותר דגימות עוצמה תקינות שמשקפות את מה שקורה בחדר.
     * במצב הזה שום דבר לא מפעיל את מנגנון ההתאוששות הקיים
     * (scheduleRecorderRecovery), וההאזנה נשארת "תקועה בשקט" עד שמשהו חיצוני
     * מפעיל startRecordingAndMonitoring() מסיבה אחרת - וזה בדיוק למה זה
     * "נראה אקראי" ולפעמים מתקן את עצמו רק אחרי כמה דקות: זה קורה בדיוק
     * כשטיימר הרענון התקופתי (RECORDER_RESTART_INTERVAL_MS, כל 4 דקות)
     * מזדמן להפעיל מחדש את ההקלטה.
     *
     * הפתרון: לא לסמוך רק על שגיאות/callbacks. כל עוד ההאזנה אמורה להיות
     * פעילה, ה-watchdog בודק כל WATCHDOG_INTERVAL_MS האם הייתה דגימה תקינה
     * (lastSuccessfulSampleMs) ב-WATCHDOG_STALE_THRESHOLD_MS האחרונות. אם
     * לא - ואין כרגע שיחה פעילה שמסבירה את זה (callCurrentlyActive) - כופים
     * הפעלה מחדש מיידית, במקום לחכות לטיימר של 4 דקות. זה מקצר את זמן
     * ההתאוששות המקסימלי מ"כמה דקות, לא עקבי" ל-~15-25 שניות קבועות בכל
     * מקרה, בלי תלות בכך שאיזשהו callback ספציפי יגיע בפועל.
     */
    private void scheduleWatchdog() {
        if (watchdogRunnable != null) {
            handler.removeCallbacks(watchdogRunnable);
        }
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                boolean stale = System.currentTimeMillis() - lastSuccessfulSampleMs > WATCHDOG_STALE_THRESHOLD_MS;
                if (stale && !callCurrentlyActive) {
                    startRecordingAndMonitoring();
                }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    private void startRecordingAndMonitoring() {
        restartScheduled = false;
        consecutiveOverThreshold = 0;
        // מאפסים את שעון ה-watchdog לרגע הזה (לא ל-0) כדי שלא "יחשוב" מיד
        // שההאזנה תקועה עוד לפני שניתנה לה הזדמנות לדגום בהצלחה בפעם
        // הראשונה אחרי ההפעלה מחדש.
        lastSuccessfulSampleMs = System.currentTimeMillis();

        // מנקים לולאות דגימה קודמות כדי לא ליצור כפילויות בכל הפעלה מחדש
        if (sampleRunnable != null) {
            handler.removeCallbacks(sampleRunnable);
        }
        stopRecorderQuietly();

        // לא שומרים את ההקלטה עצמה בכלל - רק בודקים עוצמה. הקובץ נמחק ונוצר
        // מחדש בכל מחזור כדי שלא יתפח לגודל בלתי מוגבל.
        File tempFile = new File(getCacheDir(), "monitor.3gp");
        if (tempFile.exists()) {
            tempFile.delete();
        }

        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(tempFile.getAbsolutePath());
            recorder.setOnErrorListener((mr, what, extra) -> {
                // למשל אם המיקרופון נתפס ע"י שיחה פעילה - ננסה שוב בעוד קצת זמן
                scheduleRecorderRecovery();
            });
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            // ההקלטה נכשלה (למשל המיקרופון תפוס בשיחה) - ננסה שוב בעוד קצת זמן
            scheduleRecorderRecovery();
            return;
        }

        sampleRunnable = new Runnable() {
            @Override
            public void run() {
                checkAmplitude();
                handler.postDelayed(this, SAMPLE_INTERVAL_MS);
            }
        };
        handler.postDelayed(sampleRunnable, SAMPLE_INTERVAL_MS);
    }

    /**
     * מתזמן ניסיון חוזר להפעלת ההקלטה, עם הגנה מפני תזמון כפול (אם גם
     * onError וגם checkAmplitude מזהים כשל כמעט באותו רגע).
     */
    private void scheduleRecorderRecovery() {
        stopRecorderQuietly();
        if (restartScheduled) return;
        restartScheduled = true;
        handler.postDelayed(this::startRecordingAndMonitoring, RETRY_INTERVAL_MS);
    }

    private void checkAmplitude() {
        if (recorder == null) return;
        int amplitude;
        try {
            amplitude = recorder.getMaxAmplitude();
        } catch (Exception e) {
            // ה-recorder נמצא במצב לא תקין (למשל שיחה תפסה את המיקרופון בלי
            // שהתקבל callback שגיאה מסודר). בעבר זה גרם להאזנה "להיתקע" בשקט
            // עד הפעלה מחדש ידנית - עכשיו מנסים להתאושש אוטומטית. (לולאת
            // הדגימה הנוכחית ממשיכה לרוץ בלי נזק - היא רק תמצא recorder==null
            // ותחזור מיד, עד ש-startRecordingAndMonitoring() תבטל אותה.)
            scheduleRecorderRecovery();
            return;
        }

        // דגימה תקינה התקבלה בהצלחה (גם אם העוצמה עצמה נמוכה) - מעדכנים את
        // "שומר הסף" (watchdog) שרואה בכך הוכחה שההאזנה עדיין חיה ועובדת
        // בפועל.
        lastSuccessfulSampleMs = System.currentTimeMillis();

        // הסף בפועל: ככל שהרגישות (sensitivityPercent) נמוכה יותר, כך הסף נמוך יותר
        // כלומר יותר רגיש לרעשים חלשים. טווח סביר לרעש חדר: 0-15000.
        int threshold = 1500 + (sensitivityPercent * 130); // ~1500 עד ~14500

        if (amplitude > threshold) {
            consecutiveOverThreshold++;
        } else {
            consecutiveOverThreshold = 0;
        }

        // דורשים כמה דגימות רצופות מעל הסף (רעש מתמשך, לא רעש חד-פעמי) לפני
        // שמחייגים בפועל - ראו REQUIRED_CONSECUTIVE_SAMPLES. משפר את הדיוק
        // מול רעשי רקע קצרים (טריקת דלת, שיעול וכו') בלי לפגוע משמעותית
        // בזמן התגובה לבכי אמיתי.
        if (consecutiveOverThreshold >= REQUIRED_CONSECUTIVE_SAMPLES) {
            consecutiveOverThreshold = 0;
            tryPlaceCall();
        }
    }

    private void tryPlaceCall() {
        long now = System.currentTimeMillis();
        if (now - lastCallTimeMs < CALL_COOLDOWN_MS) {
            return; // בקירור - לא מתקשרים שוב מיד
        }
        if (AppPrefs.isEmpty(phoneNumber)) {
            return;
        }
        lastCallTimeMs = now;
        triedSecondaryThisCycle = false; // מחזור חדש של "בכי" - מתחילים שוב מהמספר הראשי
        placeCallTo(phoneNumber);
    }

    /**
     * *** תיקון סעיף ה' ***
     * בעבר: אם הרשאת CALL_PHONE לא הייתה זמינה, האפליקציה נפלה חזרה למסך
     * חיוג רגיל (ACTION_DIAL) שדורש לחיצה ידנית - לא שימושי כשמדובר בילדים
     * קטנים שלא יכולים לבצע את הלחיצה הזו בעצמם.
     *
     * הפתרון החדש: ההרשאה נבדקת ונדרשת *מראש*, לפני שההאזנה בכלל מתחילה
     * (ראו MainActivity - "התחל האזנה" חסום עד שכל ההרשאות אושרו ע"י
     * מבוגר). לכן חוסר הרשאה בנקודה הזו הוא כבר מצב חריג שלא אמור לקרות
     * בזרימה הרגילה (יכול לקרות רק אם המשתמש שלל את ההרשאה ידנית
     * מהגדרות המכשיר *אחרי* שההאזנה כבר רצה). אם זה בכל זאת קורה - לא
     * מציגים מסך חיוג ידני (חסר תועלת לילד), אלא עוצרים את ההאזנה
     * ומציגים התראה בולטת וקבועה (לא נעלמת מעצמה) שמנחה את המבוגר לפתוח
     * את האפליקציה מחדש ולאשר הרשאות - כך שהבעיה לא "נעלמת בשקט".
     */
    private void placeCallTo(String numberToCall) {
        weInitiatedOutgoingCall = true;
        outgoingCallOffHookStartMs = 0;
        callInitiatedAtMs = SystemClock.elapsedRealtime();

        // *** חשוב עבור אנדרואיד 4.4 (API 19, המכשיר עם המקשים) ***
        // Context.checkSelfPermission() נוסף רק ב-API 23 - קריאה אליה בלי
        // בדיקת גרסה קודם הייתה גורמת ל-NoSuchMethodError וקריסה מיידית על
        // המכשיר הישן. במכשיר כזה (מתחת ל-API 23) אין בכלל מודל הרשאות
        // בזמן ריצה - כל הרשאה שהוצהרה ב-manifest כבר מאושרת מרגע ההתקנה,
        // ולכן אפשר להניח בבטחה שההרשאה קיימת ולדלג ישר לחיוג.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.CALL_PHONE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            weInitiatedOutgoingCall = false;
            showPermissionWarningNotification();
            stopListeningDueToPermissionLoss();
            return;
        }

        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + numberToCall));
        callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(callIntent);
        } catch (SecurityException se) {
            weInitiatedOutgoingCall = false;
            showPermissionWarningNotification();
            stopListeningDueToPermissionLoss();
        }
    }

    private void stopListeningDueToPermissionLoss() {
        getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(AppPrefs.KEY_RUNNING, false).apply();
        stopSelf();
    }

    private void showPermissionWarningNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    WARNING_CHANNEL_ID, getString(R.string.permission_denied_notification_title),
                    NotificationManager.IMPORTANCE_HIGH);
            manager.createNotificationChannel(channel);
        }
        Notification notification = buildWarningNotification();
        manager.notify(WARNING_NOTIFICATION_ID, notification);
    }

    @SuppressWarnings("deprecation")
    private Notification buildWarningNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                : android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        android.app.PendingIntent pendingIntent =
                android.app.PendingIntent.getActivity(this, 0, openApp, flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, WARNING_CHANNEL_ID)
                    .setContentTitle(getString(R.string.permission_denied_notification_title))
                    .setContentText(getString(R.string.permission_denied_notification_text))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
        } else {
            return new Notification.Builder(this)
                    .setContentTitle(getString(R.string.permission_denied_notification_title))
                    .setContentText(getString(R.string.permission_denied_notification_text))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BabySitter::MonitorLock");
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void stopRecorderQuietly() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notification_title),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build();
        } else {
            // בנאי הפרמטר-היחיד מיועד ל-API ישן יותר (כמו API 19 שאליו מכוונת
            // האפליקציה) - הוא deprecated רק ב-API חדשות יותר, שם ממילא נכנסים לענף הראשון.
            return new Notification.Builder(this)
                    .setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sampleRunnable != null) handler.removeCallbacks(sampleRunnable);
        if (restartRunnable != null) handler.removeCallbacks(restartRunnable);
        if (watchdogRunnable != null) handler.removeCallbacks(watchdogRunnable);
        stopRecorderQuietly();
        releaseWakeLock();
        unregisterPhoneStateListener();
        restoreInCallAudioSettings();
        restoreRingerVolume();
        getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(AppPrefs.KEY_RUNNING, false).apply();
    }
}
