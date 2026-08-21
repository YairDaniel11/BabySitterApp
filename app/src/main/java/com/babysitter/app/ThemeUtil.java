package com.babysitter.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * פותר את ערכת הנושא בפועל (בהיר/כהה) לפי העדפת המשתמש - סעיף ד'
 * בבקשת השיפור: שלוש אפשרויות בהגדרות (מערכת/בהיר/כהה), ברירת המחדל היא
 * "מערכת".
 *
 * לא נעשה שימוש ב-AppCompatDelegate.setDefaultNightMode (זה דורש AndroidX/
 * AppCompat, שהוסרו בכוונה מהפרויקט - ראו הערה ב-app/build.gradle). במקום
 * זאת, ה-Activity הקורא פשוט מפעיל setTheme(...) עם ה-style הרלוונטי
 * (AppTheme.Dark / AppTheme.Light, ראו styles.xml + attrs.xml) *לפני*
 * setContentView - זו הדרך הגולמית והתקנית לבחור theme בזמן ריצה, נתמכת
 * מ-API 1 ולכן בטוחה גם למכשיר היעד הישן (מינימום API 19).
 */
public final class ThemeUtil {

    private ThemeUtil() {
    }

    /** קוראים לזה ב-onCreate של כל Activity, מיד אחרי super.onCreate ולפני setContentView. */
    public static void applyTheme(Activity activity) {
        activity.setTheme(resolveDarkMode(activity) ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }

    private static boolean resolveDarkMode(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(AppPrefs.PREFS_NAME, Activity.MODE_PRIVATE);
        String mode = prefs.getString(AppPrefs.KEY_THEME_MODE, AppPrefs.THEME_SYSTEM);

        if (AppPrefs.THEME_DARK.equals(mode)) {
            return true;
        }
        if (AppPrefs.THEME_LIGHT.equals(mode)) {
            return false;
        }

        // "מערכת" (ברירת מחדל): בודקים את מצב Dark Mode של המערכת דרך
        // Configuration.uiMode - ה-API הגולמי הזה קיים מ-API 8 ואילך, ולכן
        // לא דורש AndroidX. מושג "Dark Mode כללי למערכת" עצמו קיים בפועל
        // רק מאנדרואיד 10 (Q) ואילך - במכשירים ישנים יותר (כולל מכשיר
        // המקשים היעד, אנדרואיד 4.4) הדגל הזה פשוט לא מוגדר, ואז נשארים
        // עם ערכת הנושא הכהה המקורית של האפליקציה כברירת המחדל בפועל.
        int nightModeFlags = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
            return true;
        }
        if (nightModeFlags == Configuration.UI_MODE_NIGHT_NO) {
            return false;
        }
        return true; // לא מוגדר (מכשיר ישן) - ברירת המחדל ההיסטורית של האפליקציה
    }
}
