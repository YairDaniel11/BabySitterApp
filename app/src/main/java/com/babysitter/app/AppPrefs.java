package com.babysitter.app;

import java.util.ArrayList;
import java.util.List;

/**
 * מפתחות SharedPreferences משותפים + פונקציות עזר להשוואת/פירוק מספרי
 * טלפון, שמשמשות כמה מחלקות (MainActivity, SettingsActivity,
 * CryDetectionService, AutoAnswerReceiver). מרוכז כאן במקום כפול בכל אחת
 * מהן (כפי שהיה בעבר ב-MainActivity), כדי ש-AutoAnswerReceiver (חדש - סעיף
 * ב') יוכל לגשת לאותם מפתחות/לוגיקה בלי תלות ב-MainActivity.
 */
public final class AppPrefs {

    private AppPrefs() {
    }

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

    // ערכת נושא (סעיף ד'): מערכת/בהיר/כהה - ראו ThemeUtil.
    public static final String KEY_THEME_MODE = "theme_mode";
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    // ערכי רגישות (0=הכי רגיש, 100=הכי פחות רגיש) - תואם לנוסחת הסף בשירות
    public static final int SENSITIVITY_HIGH = 0;
    public static final int SENSITIVITY_MEDIUM = 40;
    public static final int SENSITIVITY_LOW = 80;

    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static List<String> parseNumberList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        for (String part : raw.split(",")) {
            String normalized = normalizeNumber(part);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    /** משאיר רק ספרות, כדי שהשוואת מספרים לא תיכשל בגלל רווחים/מקפים/קידומת מדינה. */
    public static String normalizeNumber(String number) {
        if (number == null) return "";
        return number.replaceAll("[^0-9]", "");
    }

    /** משווה לפי סיומת המספר (9 הספרות האחרונות) כדי להתמודד עם קידומת מדינה (+972 מול 0). */
    public static boolean numberMatches(String incoming, String candidate) {
        if (incoming == null || candidate == null) return false;
        String a = normalizeNumber(incoming);
        String b = candidate; // כבר מנורמל ברשימה
        if (a.isEmpty() || b.isEmpty()) return false;
        int len = Math.min(Math.min(a.length(), b.length()), 9);
        if (len < 7) return a.equals(b); // מספרים קצרים מדי להשוואת סיומת - השוואה מלאה
        return a.substring(a.length() - len).equals(b.substring(b.length() - len));
    }
}
