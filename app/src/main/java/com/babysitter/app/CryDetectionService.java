package com.babysitter.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
 * 3. לזהות שיחה נכנסת ממספר מאושר ולענות עליה אוטומטית.
 */
public class CryDetectionService extends Service {

    private static final String CHANNEL_ID = "babysitter_channel";
    private static final int NOTIFICATION_ID = 1;

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

    // כמה זמן לחכות אחרי שזוהתה שיחה נכנסת מצלצלת ("RINGING") לפני שמנסים
    // לענות עליה אוטומטית - חלק מהמכשירים צריכים רגע עד שאפשר לענות בפועל.
    private static final long AUTO_ANSWER_DELAY_MS = 700;

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

    // הגדרות שיחה/שמע
    private boolean autoAnswerEnabled;
    private List<String> autoAnswerNumbers;
    private boolean autoAnswerSpeaker;
    private boolean allCallsSpeaker;
    private boolean muteIncomingVoice;
    private boolean muteRingerWhileActive;

    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;

    // מצב מעקב אחרי שיחה יוצאת שהאפליקציה עצמה יזמה (כדי לזהות "אין מענה")
    private boolean weInitiatedOutgoingCall = false;
    private boolean triedSecondaryThisCycle = false;
    private long outgoingCallOffHookStartMs = 0;

    // מצב מעקב כללי כדי לדעת אם השיחה הנוכחית (יוצאת או נכנסת שנענתה) שלנו
    // צריכה טיפול שמע (רמקול/השתקה), ולשחזר בסיום
    private boolean audioAdjustedForCurrentCall = false;
    private int savedRingerVolume = -1;
    private int savedVoiceCallVolume = -1;
    private String lastIncomingNumber = null;

    private boolean restartScheduled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        phoneNumber = prefs.getString(MainActivity.KEY_PHONE, "");
        secondaryPhoneNumber = prefs.getString(MainActivity.KEY_PHONE_SECONDARY, "");
        sensitivityPercent = prefs.getInt(MainActivity.KEY_SENSITIVITY, 40);

        autoAnswerEnabled = prefs.getBoolean(MainActivity.KEY_AUTO_ANSWER_ENABLED, false);
        autoAnswerNumbers = parseNumberList(prefs.getString(MainActivity.KEY_AUTO_ANSWER_NUMBERS, ""));
        // אם לא הוגדרה רשימה ייעודית למענה אוטומטי - נשתמש במספרי ההורים שהוגדרו
        if (autoAnswerNumbers.isEmpty()) {
            if (!isEmpty(phoneNumber)) autoAnswerNumbers.add(normalizeNumber(phoneNumber));
            if (!isEmpty(secondaryPhoneNumber)) autoAnswerNumbers.add(normalizeNumber(secondaryPhoneNumber));
        }
        autoAnswerSpeaker = prefs.getBoolean(MainActivity.KEY_AUTO_ANSWER_SPEAKER, true);
        allCallsSpeaker = prefs.getBoolean(MainActivity.KEY_ALL_CALLS_SPEAKER, false);
        muteIncomingVoice = prefs.getBoolean(MainActivity.KEY_MUTE_INCOMING_VOICE, false);
        muteRingerWhileActive = prefs.getBoolean(MainActivity.KEY_MUTE_RINGER_WHILE_ACTIVE, false);

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        applyRingerMuteIfNeeded();
        registerPhoneStateListener();
        startRecordingAndMonitoring();
        scheduleRecorderRestart();

        // אם השירות נהרג ע"י המערכת - שיתחיל מחדש עם אותם נתונים (ה-Intent האחרון)
        return START_REDELIVER_INTENT;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private List<String> parseNumberList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        for (String part : raw.split(",")) {
            String normalized = normalizeNumber(part);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    /** משאיר רק ספרות, כדי שהשוואת מספרים לא תיכשל בגלל רווחים/מקפים/קידומת מדינה. */
    private String normalizeNumber(String number) {
        if (number == null) return "";
        return number.replaceAll("[^0-9]", "");
    }

    /** משווה לפי סיומת המספר (9 הספרות האחרונות) כדי להתמודד עם קידומת מדינה (+972 מול 0). */
    private boolean numberMatches(String incoming, String candidate) {
        if (incoming == null || candidate == null) return false;
        String a = normalizeNumber(incoming);
        String b = candidate; // כבר מנורמל ברשימה
        if (a.isEmpty() || b.isEmpty()) return false;
        int len = Math.min(Math.min(a.length(), b.length()), 9);
        if (len < 7) return a.equals(b); // מספרים קצרים מדי להשוואת סיומת - השוואה מלאה
        return a.substring(a.length() - len).equals(b.substring(b.length() - len));
    }

    // ---------------------------------------------------------------------
    // מעקב מצב שיחה (תיקון הבאג + "אין מענה" -> מספר שני + מענה אוטומטי)
    // ---------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void registerPhoneStateListener() {
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) return;

        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                handleCallStateChanged(state, incomingNumber);
            }
        };
        try {
            telephonyManager.listen(phoneStateListener,
                    PhoneStateListener.LISTEN_CALL_STATE);
        } catch (SecurityException se) {
            // אין הרשאת READ_PHONE_STATE מסיבה כלשהי - נמשיך לפעול בלי המעקב
            // הזה (תכונות "אין מענה למספר שני" ו"מענה אוטומטי" פשוט לא יפעלו,
            // אבל גילוי בכי וחיוג ידני עדיין עובדים כרגיל).
            telephonyManager = null;
        }
    }

    private void unregisterPhoneStateListener() {
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    private void handleCallStateChanged(int state, String incomingNumber) {
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                lastIncomingNumber = incomingNumber;
                maybeAutoAnswer(incomingNumber);
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (weInitiatedOutgoingCall && outgoingCallOffHookStartMs == 0) {
                    outgoingCallOffHookStartMs = SystemClock.elapsedRealtime();
                }
                applyInCallAudioSettings();
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                handleCallEnded();
                break;
        }
    }

    private void handleCallEnded() {
        // "אין מענה" -> נסה מספר שני, רק עבור שיחה שהאפליקציה עצמה יזמה
        if (weInitiatedOutgoingCall) {
            long durationMs = outgoingCallOffHookStartMs == 0
                    ? 0
                    : SystemClock.elapsedRealtime() - outgoingCallOffHookStartMs;
            boolean likelyUnanswered = outgoingCallOffHookStartMs == 0 || durationMs < ANSWER_TIMEOUT_MS;

            weInitiatedOutgoingCall = false;
            outgoingCallOffHookStartMs = 0;

            if (likelyUnanswered && !triedSecondaryThisCycle && !isEmpty(secondaryPhoneNumber)) {
                triedSecondaryThisCycle = true;
                placeCallTo(secondaryPhoneNumber);
            }
        }

        restoreInCallAudioSettings();
        lastIncomingNumber = null;

        // תיקון הבאג המרכזי: כל שיחה שמסתיימת (בין אם שלנו, בין אם נכנסת
        // שנענתה/נדחתה) עלולה להשאיר את ה-MediaRecorder במצב לא תקין בלי
        // שיירו callback שגיאה. לכן, בלי קשר למצב ה-recorder, מפעילים מחדש
        // את ההאזנה באופן יזום ברגע שהשיחה מסתיימת - במקום לחכות להפעלה
        // מחדש ידנית או לטיימר הרענון של 4 דקות. startRecordingAndMonitoring()
        // עצמה כבר מנקה כל לולאת דגימה קודמת לפני שהיא מתחילה מחדש.
        handler.postDelayed(this::startRecordingAndMonitoring, RESTART_AFTER_CALL_DELAY_MS);
    }

    private void maybeAutoAnswer(String incomingNumber) {
        if (!autoAnswerEnabled) return;
        if (autoAnswerNumbers.isEmpty()) return;

        boolean approved = false;
        for (String candidate : autoAnswerNumbers) {
            if (numberMatches(incomingNumber, candidate)) {
                approved = true;
                break;
            }
        }
        if (!approved) return;

        handler.postDelayed(this::attemptAnswerRingingCall, AUTO_ANSWER_DELAY_MS);
    }

    private void attemptAnswerRingingCall() {
        if (telephonyManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // ה-API הרשמי (דורש הרשאת ANSWER_PHONE_CALLS) - זמין מאנדרואיד 8+.
                // חשוב: acceptRingingCall() נמצאת על TelecomManager (ניהול שיחות),
                // לא על TelephonyManager (סטטוס רשת/שיחה) - שתי מחלקות עם שמות
                // דומים אך שונות. זו הייתה שגיאת קומפילציה (cannot find symbol).
                TelecomManager telecomManager =
                        (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null) {
                    telecomManager.acceptRingingCall();
                }
            } else {
                // מכשירי אנדרואיד ישנים יותר (כמו יעד ה-minSdk 19 של האפליקציה
                // הזו) לא חשופים ל-API הרשמי. השיטה הישנה שהייתה נהוגה אז היא
                // סימולציה של לחיצה על כפתור "הדסט" (media button) - זה עבד
                // באמינות חלקית בלבד ותלוי ביצרן/גרסה, אז זו רק ניסיון best
                // effort ולא הבטחה. אם זה לא עובד במכשיר הספציפי, אין דרך
                // אמינה יותר בלי להפוך את האפליקציה לחייגן ברירת המחדל.
                simulateHeadsetHookAnswer();
            }
        } catch (SecurityException se) {
            // אין הרשאת ANSWER_PHONE_CALLS - לא ניתן לענות אוטומטית.
        }
    }

    @SuppressWarnings("deprecation")
    private void simulateHeadsetHookAnswer() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long eventTime = SystemClock.uptimeMillis();
        android.view.KeyEvent downEvent = new android.view.KeyEvent(eventTime, eventTime,
                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK, 0);
        android.view.KeyEvent upEvent = new android.view.KeyEvent(eventTime, eventTime,
                android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK, 0);
        am.dispatchMediaKeyEvent(downEvent);
        am.dispatchMediaKeyEvent(upEvent);
    }

    // ---------------------------------------------------------------------
    // שמע לשיחות (רמקול / השתקת קול נכנס / השתקת צלצול)
    // ---------------------------------------------------------------------

    private void applyRingerMuteIfNeeded() {
        if (!muteRingerWhileActive) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            savedRingerVolume = am.getStreamVolume(AudioManager.STREAM_RING);
            am.setStreamVolume(AudioManager.STREAM_RING, 0, 0);
        } catch (SecurityException ignored) {
            // בחלק מהמכשירים/גרסאות שינוי עוצמת קול דורש הרשאת "אין הפרעה" -
            // אם זה נכשל, פשוט לא משתיקים את הצלצול, אבל שאר האפליקציה תמשיך לפעול.
        }
    }

    private void restoreRingerVolume() {
        if (savedRingerVolume < 0) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                am.setStreamVolume(AudioManager.STREAM_RING, savedRingerVolume, 0);
            } catch (SecurityException ignored) {
            }
        }
        savedRingerVolume = -1;
    }

    private void applyInCallAudioSettings() {
        if (audioAdjustedForCurrentCall) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        boolean wantSpeaker = allCallsSpeaker || (autoAnswerSpeaker && lastIncomingNumber != null);
        if (wantSpeaker) {
            am.setMode(AudioManager.MODE_IN_CALL);
            am.setSpeakerphoneOn(true);
        }
        if (muteIncomingVoice) {
            savedVoiceCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0);
        }
        audioAdjustedForCurrentCall = true;
    }

    private void restoreInCallAudioSettings() {
        if (!audioAdjustedForCurrentCall) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setSpeakerphoneOn(false);
            am.setMode(AudioManager.MODE_NORMAL);
            if (savedVoiceCallVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume, 0);
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

    private void startRecordingAndMonitoring() {
        restartScheduled = false;

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

        // הסף בפועל: ככל שהרגישות (sensitivityPercent) נמוכה יותר, כך הסף נמוך יותר
        // כלומר יותר רגיש לרעשים חלשים. טווח סביר לרעש חדר: 0-15000.
        int threshold = 1500 + (sensitivityPercent * 130); // ~1500 עד ~14500

        if (amplitude > threshold) {
            tryPlaceCall();
        }
    }

    private void tryPlaceCall() {
        long now = System.currentTimeMillis();
        if (now - lastCallTimeMs < CALL_COOLDOWN_MS) {
            return; // בקירור - לא מתקשרים שוב מיד
        }
        if (isEmpty(phoneNumber)) {
            return;
        }
        lastCallTimeMs = now;
        triedSecondaryThisCycle = false; // מחזור חדש של "בכי" - מתחילים שוב מהמספר הראשי
        placeCallTo(phoneNumber);
    }

    private void placeCallTo(String numberToCall) {
        weInitiatedOutgoingCall = true;
        outgoingCallOffHookStartMs = 0;

        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + numberToCall));
        callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(callIntent);
        } catch (SecurityException se) {
            // אין הרשאת שיחה - נופלים חזרה למסך חיוג רגיל (ידרוש לחיצה ידנית)
            weInitiatedOutgoingCall = false;
            Intent dialIntent = new Intent(Intent.ACTION_DIAL);
            dialIntent.setData(Uri.parse("tel:" + numberToCall));
            dialIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(dialIntent);
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
        stopRecorderQuietly();
        releaseWakeLock();
        unregisterPhoneStateListener();
        restoreInCallAudioSettings();
        restoreRingerVolume();
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(MainActivity.KEY_RUNNING, false).apply();
    }
}
