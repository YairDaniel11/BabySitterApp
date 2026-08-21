package com.babysitter.app;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * מעבירת שיחה לרמקול - מרוכזת כאן כי גם CryDetectionService (שיחות
 * שהאפליקציה יזמה) וגם AutoAnswerReceiver (שיחות שנענו אוטומטית) צריכים
 * את זה.
 *
 * *** תיקון סעיף ו' (רמקול לא עובד באנדרואיד גבוה) ***
 * AudioManager.setSpeakerphoneOn() היא השיטה ה"קלאסית" שעבדה טוב
 * באנדרואיד 4.4 (וזו שהייתה קיימת קודם בקוד), אבל האמינות שלה יורדת
 * משמעותית באנדרואיד 9 ואילך, ומאנדרואיד 12 (API 31) גוגל הציגו API
 * חלופי מיועד בדיוק למקרה הזה - AudioManager.setCommunicationDevice() -
 * שמנתב במפורש להתקן אודיו ספציפי (במקרה שלנו: הרמקול המובנה) במקום
 * "לבקש" ניתוב בצורה שהמערכת עלולה להתעלם ממנה. בנוסף, מבקשים audio
 * focus באופן מפורש לפני הניתוב - בלי זה, במכשירים חדשים יותר קריאה
 * ל-setSpeakerphoneOn/setCommunicationDevice נוטה "לא לתפוס" כי אפליקציית
 * הטלפון המובנית עדיין מחזיקה את ה-focus.
 *
 * באנדרואיד 12+ (API 31+) משתמשים ב-setCommunicationDevice; במכשירים
 * ישנים יותר (כולל מכשיר היעד, אנדרואיד 4.4) ממשיכים עם setSpeakerphoneOn
 * הקלאסי, ששם דווח שהוא עבד היטב.
 */
public final class AudioRoutingUtil {

    private AudioRoutingUtil() {
    }

    @SuppressWarnings("deprecation")
    public static void applySpeaker(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        try {
            am.setMode(AudioManager.MODE_IN_CALL);
        } catch (SecurityException ignored) {
        }

        // מבקשים audio focus זמני (STREAM_VOICE_CALL) - עוזר לניתוב "להיתפס"
        // בפועל במקום להתעלם ממנו, בעיקר באנדרואיד חדש יותר.
        try {
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } catch (Exception ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S /* 31 */) {
            AudioDeviceInfo speaker = findBuiltInSpeaker(am);
            if (speaker != null) {
                try {
                    boolean routed = am.setCommunicationDevice(speaker);
                    if (routed) return;
                } catch (Exception ignored) {
                    // נופלים חזרה לשיטה הישנה למטה
                }
            }
        }

        try {
            am.setSpeakerphoneOn(true);
        } catch (SecurityException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    public static void clearSpeaker(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S /* 31 */) {
            try {
                am.clearCommunicationDevice();
            } catch (Exception ignored) {
            }
        }
        try {
            am.setSpeakerphoneOn(false);
        } catch (SecurityException ignored) {
        }
        try {
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (SecurityException ignored) {
        }
        try {
            am.abandonAudioFocus(null);
        } catch (Exception ignored) {
        }
    }

    private static AudioDeviceInfo findBuiltInSpeaker(AudioManager am) {
        try {
            for (AudioDeviceInfo device : am.getAvailableCommunicationDevices()) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return device;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
