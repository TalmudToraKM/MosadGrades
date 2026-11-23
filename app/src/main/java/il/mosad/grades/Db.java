package il.mosad.grades;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * מסד נתונים מקומי (SQLite). הנתונים נשמרים בטלפון בלי חיבור לרשת.
 * טבלאות: students (מתוך המערכת), exams (מבחנים קיימים מהמערכת), scores (ציונים והערות שנרשמו באפליקציה או יובאו).
 */
public class Db extends SQLiteOpenHelper {

    private static Db instance;

    public static synchronized Db get(Context ctx) {
        if (instance == null) instance = new Db(ctx.getApplicationContext());
        return instance;
    }

    private Db(Context ctx) {
        super(ctx, "mosad_grades.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE students (id INTEGER PRIMARY KEY, tz TEXT, name TEXT, className TEXT)");
        db.execSQL("CREATE TABLE exams (id INTEGER PRIMARY KEY, className TEXT, subject TEXT, title TEXT, date TEXT, year INTEGER)");
        db.execSQL("CREATE TABLE scores (examId INTEGER, studentId INTEGER, score REAL, note TEXT, PRIMARY KEY (examId, studentId))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // גרסה ראשונה - אין שדרוגים עדיין
    }

    /** רשימת הכיתות (לפי התלמידים שיובאו). */
    public List<String> classNames() {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT className FROM students WHERE className IS NOT NULL AND className != '' ORDER BY className", null);
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    /** מבחנים של כיתה, החדשים ראשונים. */
    public List<Exam> examsFor(String cls) {
        List<Exam> out = new ArrayList<Exam>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, subject, title, date FROM exams WHERE className = ? ORDER BY date DESC, id DESC",
                new String[]{cls});
        try {
            while (c.moveToNext()) {
                Exam e = new Exam();
                e.id = c.getLong(0);
                e.className = cls;
                e.subject = c.getString(1);
                e.title = c.getString(2);
                e.date = c.getString(3);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Exam exam(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT className, subject, title, date FROM exams WHERE id = ?", new String[]{String.valueOf(id)});
        try {
            if (!c.moveToFirst()) return null;
            Exam e = new Exam();
            e.id = id;
            e.className = c.getString(0);
            e.subject = c.getString(1);
            e.title = c.getString(2);
            e.date = c.getString(3);
            return e;
        } finally {
            c.close();
        }
    }

    /** תלמידי הכיתה יחד עם הציון וההערה של המבחן (אם נרשמו). */
    public List<Row> rowsFor(long examId, String cls) {
        List<Row> out = new ArrayList<Row>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT s.id, s.name, sc.score, sc.note FROM students s " +
                        "LEFT JOIN scores sc ON sc.studentId = s.id AND sc.examId = ? " +
                        "WHERE s.className = ? ORDER BY s.name",
                new String[]{String.valueOf(examId), cls});
        try {
            while (c.moveToNext()) {
                Row r = new Row();
                r.studentId = c.getLong(0);
                r.name = c.getString(1);
                r.score = c.isNull(2) ? null : Double.valueOf(c.getDouble(2));
                r.note = c.isNull(3) ? "" : c.getString(3);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * שמירת ציון והערה. ציון ריק = בלי ציון. ציון מחוץ ל-0-100 נדחה.
     * @return הודעת שגיאה, או null אם נשמר
     */
    public String save(long examId, long studentId, String scoreText, String note) {
        String t = scoreText == null ? "" : scoreText.trim().replace(',', '.');
        Double score = null;
        if (t.length() > 0) {
            try {
                score = Double.valueOf(t);
            } catch (NumberFormatException ex) {
                return "ציון לא תקין: " + scoreText;
            }
            if (score < 0 || score > 100) return "הציון חייב להיות בין 0 ל-100";
        }
        String n = note == null ? "" : note.trim();
        if (n.length() > 300) n = n.substring(0, 300);
        if (score == null && n.length() == 0) {
            getWritableDatabase().delete("scores", "examId = ? AND studentId = ?",
                    new String[]{String.valueOf(examId), String.valueOf(studentId)});
        } else {
            writeScore(examId, studentId, score, n);
        }
        return null;
    }

    /** כתיבה ישירה (משמש את הייבוא). */
    public void writeScore(long examId, long studentId, Double score, String note) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO scores (examId, studentId, score, note) VALUES (?, ?, ?, ?)",
                new Object[]{Long.valueOf(examId), Long.valueOf(studentId), score, note});
    }

    /** ערך נוכחי של ציון והערה (לצורך מיזוג בייבוא). */
    public Row existing(long examId, long studentId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT score, note FROM scores WHERE examId = ? AND studentId = ?",
                new String[]{String.valueOf(examId), String.valueOf(studentId)});
        try {
            if (!c.moveToFirst()) return null;
            Row r = new Row();
            r.score = c.isNull(0) ? null : Double.valueOf(c.getDouble(0));
            r.note = c.isNull(1) ? "" : c.getString(1);
            return r;
        } finally {
            c.close();
        }
    }

    public int count(String table) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public static class Exam {
        public long id;
        public String className, subject, title, date;

        @Override
        public String toString() {
            return (date == null ? "" : date) + "  ·  " + subject + "  ·  " + title;
        }
    }

    public static class Row {
        public long studentId;
        public String name;
        public Double score;
        public String note = "";
    }
}
