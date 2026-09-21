package com.hwanghj09.sonju;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** In-memory records only. The alternate editor deliberately invalidates a learned route. */
public class AppSkillLifecycleFixtureActivity extends Activity {
    private LinearLayout layout;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 80, 24, 24);
        setContentView(layout);
        title("기록 연습");
        button("새 기록", this::editor);
    }

    private void editor() {
        layout.removeAllViews();
        title("기록 편집");
        boolean changed = getIntent().getBooleanExtra("changed", false);
        if (changed) title("편집 화면이 업데이트되었습니다");
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(changed ? "새 기록 내용" : "기록 내용");
        layout.addView(input, new LinearLayout.LayoutParams(-1, 120));
        button("저장", () -> {
            String value = input.getText().toString();
            layout.removeAllViews();
            title("저장된 기록: " + value);
        });
    }

    private void title(String text) {
        TextView view = new TextView(this);
        view.setTextSize(24);
        view.setText(text);
        layout.addView(view);
    }

    private void button(String text, Runnable action) {
        Button view = new Button(this);
        view.setText(text);
        view.setOnClickListener(ignored -> action.run());
        layout.addView(view, new LinearLayout.LayoutParams(-1, 120));
    }
}
