package com.babysitter.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * עזרי בקשת הרשאות זמן-ריצה (runtime permissions).
 *
 * *** למה זה נחוץ עכשיו (סעיף ה' בבקשת השיפור) ***
 * בעבר targetSdk היה 19 - מתחת ל-23 - ולכן כל ההרשאות שהוצהרו ב-manifest
 * אושרו אוטומטית בהתקנה, בלי בקשה בזמן ריצה, בכל גרסת אנדרואיד. עכשיו
 * ש-targetSdk הועלה ל-34 (כדי לתקן את דיאלוג "נבנה לגרסה ישנה" - סעיף ג'),
 * המערכת עוברת למודל ההרשאות הרגיל, ואי אפשר יותר לסמוך על אישור אוטומטי.
 *
 * מכיוון שמדובר במכשיר שנשאר עם ילדים קטנים שלא יכולים להתמודד עם דיאלוג
 * הרשאות או עם מסך חיוג ידני (ACTION_DIAL) - הפתרון הוא לבקש את כל
 * ההרשאות הדרושות *מראש*, כשההורה/המבוגר האחראי לוחץ "התחל האזנה" או
 * "אפשר מענה אוטומטי" במסכי האפליקציה (שם עוד יש מבוגר שיכול לאשר),
 * ולחסום את ההפעלה לגמרי אם משהו נדחה - ראו MainActivity/SettingsActivity.
 * כך לעולם לא מגיעים למצב שבו האפליקציה מנסה להתקשר "בשקט" בלי ההרשאה
 * ונופלת חזרה למסך חיוג שהילד לא יכול להשתמש בו.
 */
public final class PermissionUtil {

    private PermissionUtil() {
    }

    /** הרשאות דרושות כדי להפעיל את האזנת הבכי + שיחה יוצאת אוטומטית. */
    public static String[] forListening() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.CALL_PHONE);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.READ_CALL_LOG);
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    /** הרשאות דרושות כדי לאפשר מענה אוטומטי לשיחות נכנסות (סעיף ב') - עצמאי מהאזנה. */
    public static String[] forAutoAnswer() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.READ_CALL_LOG);
        perms.add(Manifest.permission.ANSWER_PHONE_CALLS);
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    public static boolean hasAll(Activity activity, String[] permissions) {
        return missing(activity, permissions).length == 0;
    }

    public static String[] missing(Activity activity, String[] permissions) {
        List<String> missing = new ArrayList<>();
        // מתחת ל-API 23 אין בכלל מודל הרשאות בזמן ריצה - כל ההרשאות
        // המוצהרות ב-manifest כבר מאושרות מרגע ההתקנה.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return new String[0];
        }
        for (String permission : permissions) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }
}
