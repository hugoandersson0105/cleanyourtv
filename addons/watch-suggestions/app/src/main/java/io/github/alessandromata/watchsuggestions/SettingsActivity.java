package io.github.alessandromata.watchsuggestions;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The owner's own keys: a free TMDB key (required) and a free Trakt client id (optional). Type them with the
 * remote's keyboard, or hand them over from a computer with install.sh (see README).
 */
public class SettingsActivity extends Activity {
    private EditText tmdb, trakt;
    private Button hide;
    private boolean hideAsian;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        if (in != null && in.hasExtra("tmdb_key")) {      // install.sh: am start --es tmdb_key ... --es trakt_id ...
            Prefs.save(this, in.getStringExtra("tmdb_key"), in.getStringExtra("trakt_id") == null ? Prefs.traktId(this)
                    : in.getStringExtra("trakt_id"), Prefs.hideAsian(this));
            finish();                                         // nothing to show: install.sh only hands the keys over
            return;
        }
        hideAsian = Prefs.hideAsian(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(64), dp(40), dp(64), dp(40));
        col.addView(label(getString(R.string.settings_title), 30, Color.WHITE, true));
        col.addView(label(getString(R.string.settings_intro), 16, 0xFFB0B8C4, false), margin(8));

        col.addView(label(getString(R.string.tmdb_label), 16, Color.WHITE, true), margin(24));
        tmdb = field(Prefs.tmdbKey(this));
        col.addView(tmdb, margin(6));
        col.addView(label(getString(R.string.trakt_label), 16, Color.WHITE, true), margin(18));
        trakt = field(Prefs.traktId(this));
        col.addView(trakt, margin(6));

        hide = new Button(this);
        hide.setAllCaps(false);
        hide.setOnClickListener(v -> { hideAsian = !hideAsian; refreshHide(); });
        refreshHide();
        col.addView(hide, margin(20));

        Button save = new Button(this);
        save.setAllCaps(false);
        save.setText(R.string.save);
        save.setOnClickListener(v -> {
            Prefs.save(this, tmdb.getText().toString(), trakt.getText().toString(), hideAsian);
            finish();
        });
        col.addView(save, margin(20));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF101418);
        sv.addView(col);
        setContentView(sv);
    }

    private void refreshHide() {
        hide.setText(getString(R.string.hide_asian, getString(hideAsian ? R.string.on : R.string.off)));
    }

    private EditText field(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        return e;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams margin(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(topDp);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
