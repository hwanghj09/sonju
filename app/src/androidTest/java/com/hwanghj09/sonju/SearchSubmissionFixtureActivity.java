package com.hwanghj09.sonju;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** External test APK process. Java keeps this fixture independent of the target APK's Kotlin runtime. */
public class SearchSubmissionFixtureActivity extends Activity {
    @android.annotation.SuppressLint({"SetJavaScriptEnabled", "SetTextI18n"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("mode");
        if ("workflow_web".equals(mode)) {
            WebView web = new WebView(this);
            web.getSettings().setJavaScriptEnabled(true);
            setContentView(web);
            web.loadDataWithBaseURL("https://sonju.example/",
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>body{font:24px sans-serif;padding:20px}.card{padding:24px;margin:20px 0;border:1px solid #888}</style>" +
                "<h1>상품 목록</h1><p>가격은 상품 상세에서 확인하세요.</p>" +
                "<div role='button' tabindex='0' class='card' onclick=\"document.body.innerHTML='<h1>운동화 A 상세</h1><p>가격 59,000원</p><p>배송비 0원</p>'\"><span>운동화 A</span><p>상세 보기</p></div>" +
                "<div role='button' tabindex='0' class='card' onclick=\"document.body.innerHTML='<h1>운동화 B 상세</h1><p>가격 69,000원</p><p>배송비 3,000원</p>'\"><span>운동화 B</span><p>상세 보기</p></div>",
                "text/html", "UTF-8", null);
            return;
        }
        if ("web".equals(mode) || "deep_web".equals(mode)) {
            WebView web = new WebView(this);
            web.getSettings().setJavaScriptEnabled(true);
            setContentView(web);
            StringBuilder start = new StringBuilder(), end = new StringBuilder();
            if ("deep_web".equals(mode)) for (int i = 0; i < 40; i++) {
                start.append("<div role='group' aria-label='Layer'>"); end.append("</div>");
            }
            web.loadDataWithBaseURL("https://sonju.example/",
                "<meta name='viewport' content='width=device-width,initial-scale=1'><h1>Fixture " + mode + "</h1>" + start +
                "<form onsubmit=\"event.preventDefault();document.getElementById('result').textContent='검색 결과: '+document.getElementById('query').value+' 제출 1';\">" +
                "<label for='query'>검색</label><input id='query' type='search' aria-label='검색'>" +
                "<button type='submit'>검색</button></form><p id='result'>아직 제출하지 않았습니다</p>" + end,
                "text/html", "UTF-8", null);
            return;
        }
        TextView result = new TextView(this);
        result.setText("아직 제출하지 않았습니다");
        result.setTextSize(24);
        int[] count = {0};
        EditText editor = new EditText(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if (Build.VERSION.SDK_INT >= 30 &&
                    action == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId() &&
                    ("key".equals(mode) || "no_effect".equals(mode))) return true;
                return super.performAccessibilityAction(action, args);
            }
        };
        editor.setId(12345);
        if (!"anonymous".equals(mode)) editor.setHint("검색");
        editor.setSingleLine(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        if ("custom".equals(mode)) editor.setImeActionLabel("검색하기", 73);
        Runnable complete = () -> result.setText("검색 결과: " + editor.getText() + " 제출 " + (++count[0]));
        editor.setOnEditorActionListener((view, action, event) -> {
            if ("key".equals(mode) || "no_effect".equals(mode)) editor.setSelection(0);
            else if ("slow".equals(mode) && action == EditorInfo.IME_ACTION_SEARCH) editor.postDelayed(complete, 1200);
            else if (action == ("custom".equals(mode) ? 73 : EditorInfo.IME_ACTION_SEARCH)) complete.run();
            return true;
        });
        editor.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_ENTER || !("key".equals(mode) || "no_effect".equals(mode))) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && "key".equals(mode)) complete.run();
            return true;
        });
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 90, 24, 24);
        TextView title = new TextView(this);
        title.setText("Fixture " + mode);
        layout.addView(title);
        layout.addView(editor, new LinearLayout.LayoutParams(-1, 160));
        layout.addView(result, new LinearLayout.LayoutParams(-1, -2));
        setContentView(layout);
        editor.requestFocus();
    }
}
