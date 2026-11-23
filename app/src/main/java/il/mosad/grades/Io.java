package il.mosad.grades;

import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ייבוא וייצוא בקבצי JSON מתיקייה קבועה בטלפון: /sdcard/Mosad/
 *
 * ייבוא: קובץ "ייצוא JSON" מהמערכת (format = mosad-grades). נקראים תלמידים, מבחנים וציונים.
 *   - ערך שמגיע בקובץ מעדכן את הטלפון. ציון ריק בקובץ לא מוחק ציון שנרשם בטלפון.
 *   - מדדי קריאה לא נקראים בגרסה הזו.
 * ייצוא: קובץ JSON עם הציונים וההערות שנרשמו בטלפון, באותו מבנה. המערכת מייבאת אותו ישירות.
 */
public class Io {

    public static final String FORMAT = "mosad-grades";
    public static final String FOLDER = "Mosad";

    public static File folder() {
        File f = new File(Environment.getExternalStorageDirectory(), FOLDER);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /** קבצי JSON בתיקייה, החדשים ראשונים. */
    public static File[] jsonFiles() {
        File[] files = folder().listFiles();
        List<File> out = new ArrayList<File>();
        if (files != null) {
            for (File f : files) if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".json")) out.add(f);
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return b.lastModified() < a.lastModified() ? -1 : (b.lastModified() > a.lastModified() ? 1 : 0); }
        });
        return out.toArray(new File[out.size()]);
    }

    /** מייבא קובץ ומחזיר סיכום בעברית. זורק IOException / JSONException עם הודעה ברורה. */
    public static String importFile(Db db, File file) throws IOException, JSONException {
        JSONObject j = new JSONObject(readUtf8(file));
        if (!FORMAT.equals(j.optString("format"))) {
            throw new IOException("הקובץ אינו קובץ ציונים מהמערכת (חסר format = mosad-grades)");
        }
        JSONArray students = j.optJSONArray("students");
        JSONArray exams = j.optJSONArray("exams");
        JSONArray scores = j.optJSONArray("scores");
        int nStudents = 0, nExams = 0, nScores = 0, nSkipped = 0;

        db.getWritableDatabase().beginTransaction();
        try {
            if (students != null) for (int i = 0; i < students.length(); i++) {
                JSONObject s = students.getJSONObject(i);
                db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO students (id, tz, name, className) VALUES (?, ?, ?, ?)",
                        new Object[]{Long.valueOf(s.getLong("id")), s.optString("tz", ""), s.optString("name", ""), s.optString("className", "")});
                nStudents++;
            }
            if (exams != null) for (int i = 0; i < exams.length(); i++) {
                JSONObject e = exams.getJSONObject(i);
                db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO exams (id, className, subject, title, date, year) VALUES (?, ?, ?, ?, ?, ?)",
                        new Object[]{Long.valueOf(e.getLong("id")), e.optString("className", ""), e.optString("subject", ""),
                                e.optString("title", ""), e.optString("date", ""), Integer.valueOf(e.optInt("year", 0))});
                nExams++;
            }
            if (scores != null) for (int i = 0; i < scores.length(); i++) {
                JSONObject r = scores.getJSONObject(i);
                long examId = r.getLong("examId");
                long studentId = r.getLong("studentId");
                Db.Row old = db.existing(examId, studentId);
                Double score = r.isNull("score") ? null : Double.valueOf(r.getDouble("score"));
                String note = r.optString("note", "");
                // מיזוג: ערך חדש מהקובץ גובר, ערך ריק בקובץ לא מוחק מה שנרשם בטלפון
                if (score == null && old != null) score = old.score;
                if (note.length() == 0 && old != null) note = old.note;
                if (score == null && note.length() == 0) { nSkipped++; continue; }
                db.writeScore(examId, studentId, score, note);
                nScores++;
            }
            db.getWritableDatabase().setTransactionSuccessful();
        } finally {
            db.getWritableDatabase().endTransaction();
        }
        return "נקראו " + nStudents + " תלמידים, " + nExams + " מבחנים, " + nScores + " ציונים" +
                (nSkipped > 0 ? " (" + nSkipped + " שורות ריקות דולגו)" : "") + ".";
    }

    /** כותב את כל הציונים וההערות שבטלפון לקובץ JSON. מחזיר את הקובץ שנכתב. */
    public static File exportScores(Db db) throws IOException, JSONException {
        JSONObject out = new JSONObject();
        out.put("format", FORMAT);
        out.put("version", 1);
        out.put("source", "app");
        out.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
        JSONArray arr = new JSONArray();
        android.database.Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT examId, studentId, score, note FROM scores WHERE score IS NOT NULL OR (note IS NOT NULL AND note != '')", null);
        try {
            while (c.moveToNext()) {
                JSONObject r = new JSONObject();
                r.put("examId", c.getLong(0));
                r.put("studentId", c.getLong(1));
                if (c.isNull(2)) r.put("score", JSONObject.NULL); else r.put("score", c.getDouble(2));
                r.put("note", c.isNull(3) ? "" : c.getString(3));
                arr.put(r);
            }
        } finally {
            c.close();
        }
        out.put("scores", arr);
        File f = new File(folder(), "grades-app-" + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date()) + ".json");
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(out.toString(2));
        } finally {
            w.close();
        }
        return f;
    }

    static String readUtf8(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) bo.write(b, 0, n);
            String s = new String(bo.toByteArray(), "UTF-8");
            if (s.length() > 0 && s.charAt(0) == '﻿') s = s.substring(1);   // BOM
            return s;
        } finally {
            in.close();
        }
    }

    static String[] names(File[] files) {
        String[] out = new String[files.length];
        for (int i = 0; i < files.length; i++) out[i] = files[i].getName();
        return out;
    }
}
