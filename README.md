# בייביסיטר טלפוני — אפליקציית Android אופליין

אפליקציית Android (Java) המיועדת למכשיר "מאזין" שנשאר בחדר הילדים: היא
עוקבת אחרי עוצמת הקול דרך המיקרופון, ובזיהוי רעש/בכי חזק מחייגת אוטומטית
למספר טלפון שהוגדר מראש. המכשיר המקבל (של ההורה/המטפל/ת) אינו זקוק לאפליקציה
כלשהי — הוא רק מקבל שיחה טלפונית רגילה.

הפרויקט בנוי כך שירוץ גם על מכשירים ישנים וללא מסך מגע (`minSdk 19` —
Android 4.4 KitKat), ופועל לגמרי **אופליין**: אין שרת חיצוני, אין שירותי AI,
כל הזיהוי מתבצע מקומית על המכשיר.

---

## תוכן עניינים

1. [סקירה כללית ועקרונות תכנון](#סקירה-כללית-ועקרונות-תכנון)
2. [מבנה הפרויקט](#מבנה-הפרויקט)
3. [הרשאות (Permissions) — ומדוע כל אחת דרושה](#הרשאות-permissions--ומדוע-כל-אחת-דרושה)
4. [תיעוד קוד: `MainActivity`](#תיעוד-קוד-mainactivity)
5. [תיעוד קוד: `CryDetectionService`](#תיעוד-קוד-crydetectionservice)
6. [מפתחות SharedPreferences (טבלת עיון)](#מפתחות-sharedpreferences-טבלת-עיון)
7. [מכונת המצבים של השירות (Service State Machine)](#מכונת-המצבים-של-השירות-service-state-machine)
8. [נוסחת הרגישות לזיהוי רעש](#נוסחת-הרגישות-לזיהוי-רעש)
9. [משאבים (Resources) — עיצוב ומבנה מסך](#משאבים-resources--עיצוב-ומבנה-מסך)
10. [קבצי בנייה (Gradle)](#קבצי-בנייה-gradle)
11. [אינטגרציה רציפה (CI/CD) — GitHub Actions ו-GitLab CI](#אינטגרציה-רציפה-cicd--github-actions-ו-gitlab-ci)
12. [איך לבנות את זה ל-APK](#איך-לבנות-את-זה-ל-apk)
13. [מגבלות ידועות](#מגבלות-ידועות)
14. [יומן שינויים (Changelog)](#יומן-שינויים-changelog)

---

## סקירה כללית ועקרונות תכנון

| עיקרון | הסבר |
|---|---|
| **אופליין לחלוטין** | הזיהוי מתבצע רק עם `MediaRecorder.getMaxAmplitude()` — מדידת עוצמת קול מקומית. אין קריאות רשת, אין AI חיצוני, אין שמירת הקלטות בפועל. |
| **תמיכה במכשירים ישנים** | `minSdk 19` / `targetSdk 19` (Android 4.4 KitKat), ללא תלות ב-AndroidX/AppCompat — משתמש ב-`Activity` וב-theme הגולמי של הפלטפורמה. |
| **תמיכה במכשיר ללא מסך מגע** | ניווט מבוסס D-pad/מקשים פיזיים, `RadioGroup` במקום סליידר, `nextFocusDown` מוגדר בין כל השדות, וטיפול מיוחד במקש Back (`onKeyDown`). |
| **פרטיות** | קובץ ההקלטה הזמני (`monitor.3gp`) משמש רק למדידת עוצמה, נמחק ונוצר מחדש כל 4 דקות, ואינו נשלח לשום מקום. |
| **חוסן (Resilience)** | התאוששות אוטומטית אם המיקרופון נתפס (למשל בזמן שיחה), חידוש האזנה מיידי בסיום כל שיחה, ו-`START_REDELIVER_INTENT` כדי שהשירות יתחדש עם אותם נתונים אם המערכת הרגה אותו. |

### תרשים זרימה כללי

```
┌─────────────────┐        Start Listening        ┌──────────────────────┐
│   MainActivity   │ ─────────────────────────────▶│ CryDetectionService  │
│  (מסך הגדרות)     │                                │ (Foreground Service) │
└─────────────────┘        Stop Listening         └──────────────────────┘
                    ◀─────────────────────────────           │
                                                               │ כל 700ms
                                                               ▼
                                                  ┌────────────────────────┐
                                                  │ MediaRecorder           │
                                                  │ getMaxAmplitude()       │
                                                  └────────────────────────┘
                                                               │
                                                   amplitude > threshold?
                                                               │ כן
                                                               ▼
                                                  ┌────────────────────────┐
                                                  │ ACTION_CALL למספר       │
                                                  │ הראשי (עם קירור 60 שנ') │
                                                  └────────────────────────┘
                                                               │ אין מענה?
                                                               ▼
                                                  ┌────────────────────────┐
                                                  │ ACTION_CALL למספר       │
                                                  │ המשני (אם הוגדר)        │
                                                  └────────────────────────┘
```

---

## מבנה הפרויקט

```
BabySitterApp/
├── build.gradle                          # קובץ Gradle ברמת הפרויקט (root)
├── settings.gradle                       # רישום המודול app
├── gradle.properties                     # דגלי JVM + כיבוי AndroidX/Jetifier
├── .gitignore
├── .gitlab-ci.yml                        # פייפליין GitLab CI/CD
├── .github/
│   └── workflows/
│       └── build.yml                     # פייפליין GitHub Actions
├── README.md                             # הקובץ הזה
└── app/
    ├── build.gradle                      # קובץ Gradle ברמת המודול (SDK, dependencies)
    └── src/main/
        ├── AndroidManifest.xml           # הרשאות, רכיבים (Activity/Service)
        ├── java/com/babysitter/app/
        │   ├── MainActivity.java         # מסך ההגדרות הראשי
        │   └── CryDetectionService.java  # שירות הרקע (זיהוי + חיוג + ניהול שיחות)
        └── res/
            ├── layout/activity_main.xml  # פריסת מסך ההגדרות
            ├── values/
            │   ├── strings.xml           # כל מחרוזות הטקסט (עברית)
            │   ├── colors.xml            # פלטת הצבעים (עיצוב כהה)
            │   └── styles.xml            # ה-Theme של האפליקציה
            └── mipmap-*/                 # אייקון האפליקציה בכל צפיפויות המסך
```

---

## הרשאות (Permissions) — ומדוע כל אחת דרושה

מוגדרות ב-`AndroidManifest.xml`:

| הרשאה | לשם מה משמשת בפועל |
|---|---|
| `RECORD_AUDIO` | קריאת עוצמת הקול מהמיקרופון (`MediaRecorder`) לצורך זיהוי רעש/בכי. |
| `CALL_PHONE` | ביצוע שיחה יוצאת אוטומטית (`Intent.ACTION_CALL`) כשמזוהה רעש חזק. |
| `WAKE_LOCK` | שמירת ה-CPU ער כדי שההאזנה לא תיעצר כשהמסך כבוי (`PowerManager.PARTIAL_WAKE_LOCK`). |
| `FOREGROUND_SERVICE` | הרצת `CryDetectionService` כ-Foreground Service (עם התראה קבועה), כדי שהמערכת לא תהרוג אותו ברקע. |
| `READ_PHONE_STATE` | מעקב אחרי מצב השיחה (`PhoneStateListener`) — מזהה מצלצל/מחובר/הסתיים, כדי לזהות "אין מענה" ולחזור להאזין מיד בסיום שיחה. |
| `READ_CALL_LOG` | נדרש מ-Android 10 ואילך כדי לקבל את מספר השיחה הנכנסת בפועל (יחד עם `READ_PHONE_STATE`), לצורך התאמה מול רשימת "מספרים למענה אוטומטי". |
| `ANSWER_PHONE_CALLS` | מאפשר מענה אוטומטי לשיחה נכנסת דרך `TelecomManager.acceptRingingCall()` (Android 8+). |
| `MODIFY_AUDIO_SETTINGS` | שליטה בעוצמת הרמקול/צלצול ובמצב הרמקול (Speaker) בזמן שיחה. |

הצהרות `<uses-feature>` נוספות:

| Feature | required | הסבר |
|---|---|---|
| `android.hardware.touchscreen` | `false` | חובה להצהיר על כך במפורש, אחרת אנדרואיד מניח כברירת מחדל שהאפליקציה דורשת מסך מגע ועלול לחסום התקנה במכשיר ללא מגע. |
| `android.hardware.microphone` | `true` | האפליקציה לא פונקציונלית בלי מיקרופון. |
| `android.hardware.telephony` | `true` | האפליקציה לא פונקציונלית בלי יכולת חיוג. |

---

## תיעוד קוד: `MainActivity`

**מיקום:** `app/src/main/java/com/babysitter/app/MainActivity.java`
**חבילה:** `com.babysitter.app`
**יורש מ:** `android.app.Activity` (לא `AppCompatActivity` — ראו הסבר בהערות בקובץ)

מסך ההגדרות היחיד באפליקציה. תפקידו לאסוף מהמשתמש את כל ההגדרות, לשמור
אותן ב-`SharedPreferences`, ולהפעיל/לעצור את `CryDetectionService`.

### קבועים ציבוריים (`public static final`)

אלו הם שמות ה-keys שנשמרים ב-`SharedPreferences` (קובץ `babysitter_prefs`),
ומשמשים גם את `CryDetectionService` לקריאת אותן הגדרות:

```java
PREFS_NAME                    // "babysitter_prefs"
KEY_PHONE                     // מספר הטלפון הראשי לחיוג
KEY_PHONE_SECONDARY           // מספר טלפון משני (fallback אם אין מענה)
KEY_SENSITIVITY               // רמת רגישות (0-100)
KEY_RUNNING                   // האם ההאזנה פעילה כרגע (boolean)
KEY_AUTO_ANSWER_ENABLED       // האם מענה אוטומטי לשיחות נכנסות מופעל
KEY_AUTO_ANSWER_NUMBERS       // רשימת מספרים מאושרים למענה אוטומטי (מופרדת בפסיקים)
KEY_AUTO_ANSWER_SPEAKER       // האם לענות אוטומטית דרך רמקול
KEY_ALL_CALLS_SPEAKER         // האם כל השיחות (גם יוצאות) יעברו לרמקול
KEY_MUTE_INCOMING_VOICE       // השתקת קול הצד השני בשיחה נכנסת
KEY_MUTE_RINGER_WHILE_ACTIVE  // השתקת צלצול כללי בזמן שההאזנה פעילה
```

### שיטות עיקריות

| שיטה | תיאור |
|---|---|
| `onCreate()` | טוען את ה-Views, טוען ערכים שמורים מ-`SharedPreferences` לתוך שדות המסך, וקושר מאזיני לחיצה. |
| `openContactPicker(int requestCode)` | פותח את בורר אנשי הקשר המובנה של המכשיר (`Intent.ACTION_PICK`), לפי קוד בקשה (ראשי/משני/מענה-אוטומטי). |
| `onActivityResult(...)` | מטפל בתוצאה מבורר אנשי הקשר, קורא את המספר שנבחר ומכניס אותו לשדה המתאים. |
| `readPhoneNumberFromContactUri(Uri)` | שאילתת `ContentResolver` להוצאת מספר הטלפון בפועל מתוך ה-URI שמחזיר בורר אנשי הקשר. |
| `onKeyDown(int, KeyEvent)` | טיפול מותאם במקש Back: אם שדה מספר בפוקוס — מוחק תו אחרון (ולא סוגר את האפליקציה). |
| `setSensitivitySelection(int)` / `getSelectedSensitivity()` | תרגום בין ערך רגישות מספרי (0/40/80) לבין הבחירה ב-`RadioGroup`. |
| `startListening()` | שומר את כל ההגדרות הנוכחיות ב-`SharedPreferences`, ומפעיל את `CryDetectionService` (`startForegroundService` מ-Android 8+, אחרת `startService`). |
| `stopListening()` | מסמן `KEY_RUNNING=false` ועוצר את השירות (`stopService`). |
| `updateUiState(boolean running)` | מעדכן טקסט סטטוס וכפתור, ומנעל/פותח את כל שדות הקלט לפי מצב הפעלה. |

### קודי בקשה לבורר אנשי קשר

```java
REQUEST_PICK_CONTACT_PRIMARY    = 1001   // מספר ראשי
REQUEST_PICK_CONTACT_SECONDARY  = 1002   // מספר משני
REQUEST_PICK_CONTACT_AUTO_ANSWER = 1003  // הוספה לרשימת מענה אוטומטי
```

### ערכי רגישות

```java
SENSITIVITY_HIGH   = 0    // הכי רגיש (כל רעש קטן)
SENSITIVITY_MEDIUM = 40   // רגיל (ברירת מחדל)
SENSITIVITY_LOW    = 80   // הכי פחות רגיש (רק רעש חזק)
```

---

## תיעוד קוד: `CryDetectionService`

**מיקום:** `app/src/main/java/com/babysitter/app/CryDetectionService.java`
**יורש מ:** `android.app.Service`
**סוג:** Foreground Service (עם התראה קבועה, `NOTIFICATION_ID = 1`)

הליבה של האפליקציה. רץ ברקע כל עוד ההאזנה פעילה, ואחראי על שלושה תפקידים
במקביל:

1. **זיהוי רעש/בכי** — דגימת עוצמת קול כל `SAMPLE_INTERVAL_MS` (700ms).
2. **ניהול שיחה יוצאת** — חיוג למספר הראשי, ובמקרה של "אין מענה" — fallback
   למספר המשני.
3. **מענה אוטומטי לשיחות נכנסות** — ממספרים מאושרים בלבד, כולל הגדרות שמע
   (רמקול/השתקה).

### קבועי תזמון (`private static final`)

| קבוע | ערך | משמעות |
|---|---|---|
| `SAMPLE_INTERVAL_MS` | 700 | תדירות בדיקת עוצמת הקול. |
| `CALL_COOLDOWN_MS` | 60,000 | זמן מינימלי בין חיוג לחיוג (מונע חיוגים חוזרים ברצף). |
| `RECORDER_RESTART_INTERVAL_MS` | 240,000 (4 דק') | הפעלה מחדש תקופתית של ההקלטה, כדי שהקובץ הזמני לא יתפח. |
| `RETRY_INTERVAL_MS` | 3,000 | זמן המתנה לפני ניסיון חוזר אחרי כשל בהכנת ה-Recorder. |
| `ANSWER_TIMEOUT_MS` | 15,000 | סף הערכה (heuristic) ל"אין מענה" בשיחה יוצאת — משך זמן ב-OFFHOOK הקצר מכך נחשב כלא נענה. |
| `AUTO_ANSWER_DELAY_MS` | 700 | השהיה בין זיהוי שיחה מצלצלת לבין ניסיון מענה אוטומטי. |
| `RESTART_AFTER_CALL_DELAY_MS` | 800 | השהיה אחרי סיום שיחה לפני חידוש ההאזנה (לתת למיקרופון להשתחרר). |

> **הערה חשובה על `ANSWER_TIMEOUT_MS`:** אין ב-Android API ציבורי דרך אמינה
> להבדיל "שיחה נענתה" מ"שיחה לא נענתה" בלי להיות אפליקציית החייגן ברירת
> המחדל של המערכת. לכן זו הערכה סטטיסטית בלבד, מבוססת משך הזמן שהטלפון
> נשאר במצב `OFFHOOK`.

### מחזור החיים (`Lifecycle`)

| שיטה | מתי נקראת | מה עושה |
|---|---|---|
| `onCreate()` | פעם אחת עם יצירת השירות | יוצר `Handler` ו-notification channel. |
| `onStartCommand(...)` | בכל `startService`/`startForegroundService` | טוען הגדרות מ-`SharedPreferences`, מפעיל foreground notification, נועל wake lock, רושם מאזין מצב שיחה, ומתחיל הקלטה+דגימה. מחזיר `START_REDELIVER_INTENT`. |
| `onDestroy()` | בעצירת השירות | מנקה הכל: מפסיק דגימה, משחרר recorder, wake lock, מאזין טלפון, ומחזיר הגדרות שמע/צלצול למצבן המקורי. |
| `onBind(Intent)` | — | תמיד מחזיר `null` (זה לא Bound Service). |

### זיהוי רעש (Amplitude Detection)

```java
startRecordingAndMonitoring()   // מאתחל MediaRecorder חדש ולולאת דגימה
checkAmplitude()                // דוגם את recorder.getMaxAmplitude(), משווה לסף
tryPlaceCall()                  // אם עבר את הסף וחלף זמן הקירור — מחייג
scheduleRecorderRecovery()      // בכשל הקלטה - מתזמן ניסיון חוזר
scheduleRecorderRestart()       // הפעלה מחדש תקופתית (כל 4 דק') למניעת תפיחת קובץ
```

הקלטה נשמרת לקובץ זמני בלבד (`getCacheDir()/monitor.3gp`, פורמט
`THREE_GPP`/`AMR_NB`) — **התוכן עצמו אף פעם לא נקרא או נשלח לשום מקום**,
נבדקת רק עוצמת השיא (`getMaxAmplitude()`) ואז הקובץ נמחק ונוצר מחדש.

### מעקב מצב שיחה (`PhoneStateListener`)

```java
handleCallStateChanged(state, incomingNumber)
    ├── CALL_STATE_RINGING  → maybeAutoAnswer(incomingNumber)
    ├── CALL_STATE_OFFHOOK  → applyInCallAudioSettings() + התחלת מדידת "אין מענה"
    └── CALL_STATE_IDLE     → handleCallEnded()
```

`handleCallEnded()` הוא גם מקום **תיקון הבאג המרכזי** בפרויקט: בעבר, ניתוק
שיחה (יוצאת/נכנסת, שלנו או לא) יכול היה להשאיר את ה-`MediaRecorder` במצב לא
תקין בלי callback שגיאה מסודר, מה שגרם להאזנה "להיתקע" בשקט עד הפעלה מחדש
ידנית. כעת, **כל** סיום שיחה מפעיל מחדש את ההאזנה באופן יזום.

### מענה אוטומטי לשיחות נכנסות

```java
maybeAutoAnswer(incomingNumber)     // בודק אם המספר ברשימה המאושרת
attemptAnswerRingingCall()          // מנסה לענות בפועל
    ├── Android 8+   → TelecomManager.acceptRingingCall()  [API רשמי]
    └── מתחת ל-8      → simulateHeadsetHookAnswer()          [best-effort, סימולציית לחיצת הדסט]
numberMatches(incoming, candidate)  // השוואה לפי 9 הספרות האחרונות, כדי להתעלם מקידומת מדינה (+972 מול 0)
```

> אם `autoAnswerNumbers` ריק (המשתמש לא הגדיר רשימה ייעודית), השירות נופל
> חזרה אוטומטית למספרי ההורה (הראשי + המשני) שהוגדרו במסך הראשי.

### הגדרות שמע לשיחה

```java
applyRingerMuteIfNeeded()      // השתקת צלצול כללי בזמן שההאזנה פעילה (ואם כן — שמירת העוצמה המקורית)
applyInCallAudioSettings()     // הפעלת רמקול ו/או השתקת קול הצד השני, לפי הגדרות המשתמש
restoreInCallAudioSettings()   // שחזור מצב שמע רגיל בסיום השיחה
restoreRingerVolume()          // שחזור עוצמת הצלצול המקורית
```

---

## מפתחות SharedPreferences (טבלת עיון)

קובץ ההעדפות: **`babysitter_prefs`** (`MODE_PRIVATE`)

| מפתח | סוג | ברירת מחדל | נקרא ע"י |
|---|---|---|---|
| `phone_number` | String | `""` | שני הקבצים |
| `phone_number_secondary` | String | `""` | שני הקבצים |
| `sensitivity` | int | `40` | שני הקבצים |
| `is_running` | boolean | `false` | `MainActivity` |
| `auto_answer_enabled` | boolean | `false` | שני הקבצים |
| `auto_answer_numbers` | String | `""` | שני הקבצים |
| `auto_answer_speaker` | boolean | `true` | שני הקבצים |
| `all_calls_speaker` | boolean | `false` | שני הקבצים |
| `mute_incoming_voice` | boolean | `false` | שני הקבצים |
| `mute_ringer_while_active` | boolean | `false` | שני הקבצים |

---

## מכונת המצבים של השירות (Service State Machine)

```
                     startListening()
                            │
                            ▼
                     ┌─────────────┐
              ┌─────▶│   מאזין      │◀─────┐
              │      └─────────────┘       │
              │             │              │ RESTART_AFTER_CALL_DELAY_MS
   4 דקות     │      amplitude > threshold │  (אחרי כל סיום שיחה)
   (רענון)    │             │              │
              │             ▼              │
              │      ┌─────────────┐       │
              └──────│ מחייג ליעד   │───────┘
                     └─────────────┘
                            │ CALL_STATE_IDLE (אין מענה)
                            ▼
                     ┌─────────────┐
                     │ מחייג ליעד   │
                     │  המשני       │
                     └─────────────┘
```

בכל רגע `stopListening()` (מ-`MainActivity`) עוצר את השירות לחלוטין
ומחזיר את כל מצבי השמע/צלצול למצבם המקורי (`onDestroy()`).

---

## נוסחת הרגישות לזיהוי רעש

```java
int threshold = 1500 + (sensitivityPercent * 130);  // טווח: ~1500 עד ~14500
if (amplitude > threshold) { tryPlaceCall(); }
```

`sensitivityPercent` הוא ערך 0–100 שנשמר ב-`KEY_SENSITIVITY` (0=רגיש מאוד,
100=רגיש הכי פחות). ככל שהערך נמוך יותר — הסף נמוך יותר, כלומר האפליקציה
מגיבה לרעשים חלשים יותר.

| בחירה במסך | ערך שמור | סף בפועל |
|---|---|---|
| רגיש מאוד | 0 | 1,500 |
| רגיל | 40 | 6,700 |
| פחות רגיש | 80 | 11,900 |

---

## משאבים (Resources) — עיצוב ומבנה מסך

### פלטת צבעים (`values/colors.xml`)

| שם | ערך | שימוש |
|---|---|---|
| `bg_dark` | `#FF2B2E33` | רקע המסך |
| `header_black` | `#FF000000` | רצועת כותרת עליונה |
| `accent_teal` | `#FF00BCD4` | הדגשה (טורקיז) — כותרות, כפתורים |
| `text_white` | `#FFFFFFFF` | טקסט ראשי |
| `text_light_gray` | `#FFCCCCCC` | טקסט משני/תוויות |
| `divider_gray` | `#FF444444` | קווי הפרדה |
| `row_gray` | `#FF3A3E44` | רקע שדות קלט |

עיצוב שטוח לחלוטין (Flat), ללא צללים/גרדיאנטים, מבוסס על ה-theme הגולמי
של הפלטפורמה (`android:Theme`) ולא Material — כדי להישאר תואם למכשירים
ישנים ללא AndroidX.

### `activity_main.xml` — מבנה המסך

```
LinearLayout (אנכי)
├── רצועת כותרת (header_black + accent_teal)
└── ScrollView
    └── LinearLayout (אנכי)
        ├── statusText                     — טקסט סטטוס (מאזין / כבוי)
        ├── phoneNumberInput + phoneContactButton         — מספר ראשי
        ├── secondaryPhoneNumberInput + secondaryContactButton — מספר משני
        ├── [מפריד]
        ├── auto_answer_section_title
        ├── autoAnswerEnabledCheck
        ├── autoAnswerNumbersInput + autoAnswerContactButton
        ├── autoAnswerSpeakerCheck
        ├── [מפריד]
        ├── audio_section_title
        ├── allCallsSpeakerCheck
        ├── muteIncomingVoiceCheck
        ├── muteRingerWhileActiveCheck
        ├── [מפריד]
        ├── sensitivityGroup (RadioGroup: high/medium/low)
        └── startStopButton
```

כל שדה מוגדר עם `android:nextFocusDown` כדי לאפשר ניווט רציף עם חצים/מקשים
פיזיים ללא צורך במסך מגע.

---

## קבצי בנייה (Gradle)

### `build.gradle` (root)

```groovy
buildscript {
    dependencies {
        classpath 'com.android.tools.build:gradle:7.4.2'   // Android Gradle Plugin (AGP)
    }
}
```

### `app/build.gradle`

| הגדרה | ערך | הערה |
|---|---|---|
| `namespace` | `com.babysitter.app` | |
| `compileSdk` | `33` | גרסת ה-API שמולה מתקמפלים (לא בהכרח = targetSdk). |
| `minSdk` / `targetSdk` | `19` | Android 4.4 KitKat — תואם למכשיר היעד. |
| `sourceCompatibility` / `targetCompatibility` | Java 8 | |

**ללא תלויות AndroidX/AppCompat בכלל** — נבדק מול שתי אפליקציות אמיתיות
שרצות בהצלחה על מכשירי שיאומי מקשים ישנים.

### `gradle.properties`

```properties
android.useAndroidX=false
android.enableJetifier=false
```

חשוב להשאיר את שני הדגלים על `false` — אחרת חלק מהכלים ינסו להוסיף
תלויות AndroidX בכל זאת.

---

## אינטגרציה רציפה (CI/CD) — GitHub Actions ו-GitLab CI

הפרויקט כולל **שני** pipelines מקבילים ושקולים פונקציונלית, כדי לאפשר
בנייה אוטומטית בענן בלי להתקין דבר מקומית:

| קובץ | פלטפורמה | תוצר |
|---|---|---|
| `.gitlab-ci.yml` | GitLab CI/CD | APK כ-Job Artifact (זמני, 30 יום) |
| `.github/workflows/build.yml` | GitHub Actions | APK כ-Workflow Artifact **וגם** מצורף ל-GitHub Release קבוע |

שניהם:
1. רצים בתוך container של תמונת Docker הרשמית `gradle:7.6.4-jdk17`.
2. מורידים ומתקינים Android `cmdline-tools`, `platform-tools`,
   `platforms;android-33` ו-`build-tools;33.0.2`.
3. שומרים cache של ה-SDK וה-Gradle home בין ריצות, כדי לחסוך זמן.
4. מריצים `gradle assembleDebug --stacktrace --console=plain`.
5. מעלים את `app/build/outputs/apk/debug/app-debug.apk` כ-artifact.

> **הערה גרסת JDK:** ה-container חייב JDK 17 (לא JDK 11) כי כלי
> `sdkmanager` העדכניים בגרסת ה-`cmdline-tools` הנוכחית קומפלו מול Java 17
> ולא רצים על JDK 11 (`UnsupportedClassVersionError`). זה **לא** קשור
> ל-`minSdk`/`targetSdk` של האפליקציה עצמה, שנשארים 19 — זו רק גרסת ה-Java
> שמריצה את **כלי הבנייה** בשרת ה-CI.

### הרצת GitHub Release (רק ב-GitHub Actions)

צעד נוסף (`softprops/action-gh-release`) שיוצר Release קבוע בעמוד
"Releases" של המאגר, עם ה-APK מצורף, בכל push לענף `main`/`master`.
דורש הרשאת `contents: write` על ה-`GITHUB_TOKEN` — מוגדרת ברמת ה-workflow,
אך ייתכן שצריך גם לוודא ב-**Settings → Actions → General → Workflow
permissions** שמוגדר "Read and write permissions", אחרת הצעד הזה עלול
להיכשל למרות שהבנייה עצמה הצליחה.

---

## איך לבנות את זה ל-APK

### אופציה א׳: Android Studio (מקומי)

1. התקינו [Android Studio](https://developer.android.com/studio).
2. `File > Open` ובחרו את תיקיית `BabySitterApp`.
3. המתינו לסנכרון Gradle (מוריד תלויות מהאינטרנט).
4. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
5. ה-APK ייווצר ב-`app/build/outputs/apk/debug/app-debug.apk`.
6. העבירו למכשיר (USB/וואטסאפ) והתקינו ("התקנה ממקורות לא ידועים" צריכה
   להיות מופעלת).

### אופציה ב׳: GitLab CI/CD

1. צרו מאגר ריק ב-GitLab, ודחפו אליו את כל תוכן התיקייה.
2. עברו ל-`Build > Pipelines` — הריצה מתחילה אוטומטית.
3. בסיום, ה-APK זמין כ-Job Artifact של `assembleDebug`.

### אופציה ג׳: GitHub Actions

1. צרו מאגר ריק ב-GitHub, ודחפו אליו את כל תוכן התיקייה (ל-`main` או
   `master`).
2. עברו ל-טאב `Actions` — הריצה מתחילה אוטומטית.
3. בסיום, ה-APK זמין גם כ-Artifact של ה-workflow וגם בעמוד `Releases`.

בשתי אופציות הענן, הריצה הראשונה איטית יותר (הורדת SDK), וריצות הבאות
מהירות בזכות ה-cache.

---

## מגבלות ידועות

- **זיהוי לפי עוצמת קול בלבד, לא זיהוי בכי אמיתי** — אין רכיב AI; כל רעש
  חזק (לא רק בכי) יכול להפעיל חיוג. זהו טרייד-אוף מכוון לטובת פעולה
  אופליין, פרטית וללא עלות.
- לאחר כל שיחה יש "קירור" של דקה לפני חיוג חוזר, כדי למנוע חיוגים ברצף.
- מומלץ לוודא במכשיר בפועל שהאפליקציה אינה נסגרת ע"י חוסכי סוללה
  אגרסיביים (למשל ב-MIUI יש להוסיף אותה לרשימת "ללא הגבלת סוללה").
- אם הרשאת `CALL_PHONE` אינה זמינה מסיבה כלשהי, האפליקציה נופלת חזרה
  למסך חיוג רגיל (`ACTION_DIAL`) שדורש לחיצה ידנית, במקום שיחה אוטומטית.
- זיהוי "אין מענה" לשיחה יוצאת הוא הערכה (heuristic) מבוססת-זמן, לא זיהוי
  ודאי — ראו הסבר ב-`ANSWER_TIMEOUT_MS`.
- מענה אוטומטי לשיחות נכנסות במכשירים מתחת ל-Android 8 מתבסס על סימולציית
  לחיצת "הדסט" (`KEYCODE_HEADSETHOOK`) — best-effort בלבד, תלוי יצרן/גרסה.

---

## יומן שינויים (Changelog)

### לאחר השוואה לשתי אפליקציות אמיתיות שרצות על שיאומי מקשים
- הוסרו כל תלויות AndroidX/AppCompat; `MainActivity` עבר ל-`Activity` רגיל
  ול-theme הגולמי של הפלטפורמה.
- `targetSdk` הותאם בדיוק ל-`minSdk` (19).
- עיצוב מחדש למוסכמת "רקע כהה + הדגשת טורקיז" שנמצאה במכשירי היעד.
- הוסר שימוש ב-`RECEIVE_BOOT_COMPLETED` (הרשאה שלא הייתה בשימוש בפועל).

### תיקוני יציבות
- תוקן באג שבו ניתוק שיחה יכול "לתקוע" את ההאזנה בשקט עד הפעלה מחדש ידנית
  — כעת כל סיום שיחה מפעיל מחדש את ההאזנה באופן יזום.
- נוספה הפעלה מחדש תקופתית של ההקלטה (כל 4 דקות) כדי שהקובץ הזמני לא יתפח.
- נוספה התאוששות אוטומטית כשהמיקרופון תפוס (למשל בזמן שיחה פעילה).
- הוחלף סליידר (SeekBar) לבחירת רגישות בשלושה כפתורי בחירה — ניווט אמין
  יותר עם מקשים פיזיים.

### תיקוני CI/CD
- עדכון תמונת ה-Docker בשני ה-pipelines מ-`gradle:7.6.4-jdk11` ל-
  `gradle:7.6.4-jdk17`, בעקבות קריסת `sdkmanager`
  (`UnsupportedClassVersionError`) — כלי ה-`cmdline-tools` הנוכחיים דורשים
  Java 17.
- תוקנה שגיאת קומפילציה ב-`CryDetectionService.java`: הקריאה
  `telephonyManager.acceptRingingCall()` הוחלפה בקריאה נכונה על
  `TelecomManager.acceptRingingCall()` (שתי מחלקות שונות עם שמות דומים —
  המתודה קיימת רק ב-`TelecomManager`).
