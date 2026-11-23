package il.mosad.grades;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/** רשימת המבחנים של כיתה. בחירה במבחן פותחת את גיליון הציונים שלו. */
public class ExamListActivity extends Activity {

    private List<Db.Exam> exams;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        final String cls = getIntent().getStringExtra("cls");
        ((TextView) findViewById(R.id.title)).setText("כיתה " + cls + " - מבחנים");
        exams = Db.get(this).examsFor(cls);
        ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(new ArrayAdapter<Db.Exam>(this, android.R.layout.simple_list_item_1, exams));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent i = new Intent(ExamListActivity.this, ExamActivity.class);
                i.putExtra("examId", exams.get(position).id);
                startActivity(i);
            }
        });
    }
}
