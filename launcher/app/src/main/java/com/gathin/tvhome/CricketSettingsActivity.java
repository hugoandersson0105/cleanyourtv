package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Scores on or off, the API key, and the team list: order, add a code, remove a code. */
public final class CricketSettingsActivity extends Activity {
    private SharedPreferences prefs;
    private TextView showChoice;
    private TextView keyChoice;
    private TextView clearChoice;
    private TextView statusView;
    private LinearLayout teamsView;
    private int pendingFocus = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_cricket_settings);
        prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        CricketSecret.importExternal(this);
        showChoice = findViewById(R.id.cricketShow);
        keyChoice = findViewById(R.id.cricketKey);
        clearChoice = findViewById(R.id.cricketClear);
        statusView = findViewById(R.id.cricketStatus);
        teamsView = findViewById(R.id.cricketTeams);
        showChoice.setOnClickListener(v -> toggle());
        keyChoice.setOnClickListener(v -> askKey());
        clearChoice.setOnClickListener(v -> confirmClear());
        findViewById(R.id.cricketAdd).setOnClickListener(v -> askCode());
        refresh();
        showChoice.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (CricketSecret.importExternal(this)) refresh();
    }

    private void toggle() {
        if (prefs.getBoolean(CricketPanel.ENABLED, false)) {
            prefs.edit().putBoolean(CricketPanel.ENABLED, false).apply();
            refresh();
            return;
        }
        if (!CricketSecret.exists(this)) {
            askKey();
            return;
        }
        prefs.edit().putBoolean(CricketPanel.ENABLED, true).apply();
        refresh();
    }

    private void askKey() {
        EditText input = field(getString(R.string.cricket_key_entry));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle(R.string.cricket_key_title)
                .setMessage(R.string.cricket_key_message)
                .setView(box(input))
                .setPositiveButton(R.string.cricket_save, (dialog, which) -> saveKey(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveKey(String key) {
        if (!CricketSecret.looksValid(key)) {
            Toast.makeText(this, R.string.cricket_key_bad, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            CricketSecret.put(this, key);
        } catch (Exception e) {
            Toast.makeText(this, R.string.cricket_key_failed, Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putBoolean(CricketPanel.ENABLED, true).apply();
        Toast.makeText(this, R.string.cricket_key_saved, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.cricket_clear_confirm)
                .setPositiveButton(R.string.cricket_clear_key, (dialog, which) -> {
                    CricketSecret.clear(this);
                    prefs.edit().putBoolean(CricketPanel.ENABLED, false).apply();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askCode() {
        EditText input = field(getString(R.string.cricket_add_hint));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle(R.string.cricket_add_title)
                .setView(box(input))
                .setPositiveButton(R.string.cricket_add, (dialog, which) -> addCode(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addCode(String raw) {
        List<String> codes = teams();
        switch (CricketTeams.add(codes, raw)) {
            case BAD:
                Toast.makeText(this, R.string.cricket_bad_code, Toast.LENGTH_LONG).show();
                return;
            case DUPLICATE:
                Toast.makeText(this, R.string.cricket_duplicate, Toast.LENGTH_SHORT).show();
                return;
            case FULL:
                Toast.makeText(this, R.string.cricket_full, Toast.LENGTH_LONG).show();
                return;
            case OK:
                break;
            default:
                return;
        }
        saveTeams(codes);
        pendingFocus = codes.size() - 1;
        refresh();
    }

    private void refresh() {
        boolean on = prefs.getBoolean(CricketPanel.ENABLED, false);
        boolean saved = CricketSecret.exists(this);
        showChoice.setText(getString(R.string.cricket_show, getString(on ? R.string.on : R.string.off)));
        keyChoice.setText(getString(R.string.cricket_key,
                getString(saved ? R.string.cricket_key_set : R.string.cricket_key_missing)));
        clearChoice.setVisibility(saved ? View.VISIBLE : View.GONE);
        String status = prefs.getString(CricketPanel.STATUS, "");
        if (status.isEmpty()) {
            status = getString(saved ? R.string.cricket_status_waiting : R.string.cricket_status_needs_key);
        }
        statusView.setText(status);
        List<String> codes = teams();
        teamsView.removeAllViews();
        for (int i = 0; i < codes.size(); i++) {
            teamsView.addView(row(codes, i));
        }
        if (pendingFocus >= 0 && teamsView.getChildCount() > 0) {
            int index = Math.min(pendingFocus, teamsView.getChildCount() - 1);
            View row = teamsView.getChildAt(index);
            pendingFocus = -1;
            row.requestFocus();
        }
    }

    private LinearLayout row(List<String> codes, int index) {
        String code = codes.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);

        TextView label = new TextView(this);
        String known = CricketTeams.label(code);
        label.setText(known.isEmpty()
                ? getString(R.string.cricket_custom, code)
                : code + "    " + known);
        label.setTextColor(Color.WHITE);
        label.setTextSize(18);
        label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        label.setLayoutParams(labelParams);
        row.addView(label);

        TextView up = button(getString(R.string.cricket_up), getString(R.string.cricket_move_up, code));
        TextView down = button(getString(R.string.cricket_down), getString(R.string.cricket_move_down, code));
        TextView remove = button(getString(R.string.cricket_remove), getString(R.string.cricket_remove_team, code));
        up.setEnabled(index > 0);
        up.setAlpha(index > 0 ? 1f : 0.35f);
        down.setEnabled(index < codes.size() - 1);
        down.setAlpha(index < codes.size() - 1 ? 1f : 0.35f);
        up.setOnClickListener(v -> change(index, -1));
        down.setOnClickListener(v -> change(index, 1));
        remove.setOnClickListener(v -> {
            List<String> current = teams();
            CricketTeams.remove(current, index);
            saveTeams(current);
            pendingFocus = index;
            refresh();
        });
        row.addView(up);
        row.addView(down);
        row.addView(remove);
        return row;
    }

    private void change(int index, int delta) {
        List<String> codes = teams();
        CricketTeams.move(codes, index, delta);
        saveTeams(codes);
        pendingFocus = index + delta;
        refresh();
    }

    private TextView button(String label, String description) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setContentDescription(description);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackgroundResource(R.drawable.settings_choice_bg);
        int pad = dp(12);
        view.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(108), dp(48));
        params.setMarginStart(dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private List<String> teams() {
        return CricketTeams.load(prefs.contains(CricketPanel.TEAMS) ? prefs.getString(CricketPanel.TEAMS, "") : null);
    }

    private void saveTeams(List<String> codes) {
        prefs.edit().putString(CricketPanel.TEAMS, CricketTeams.join(codes)).apply();
    }

    private EditText field(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return input;
    }

    private FrameLayout box(EditText input) {
        FrameLayout box = new FrameLayout(this);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input);
        return box;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
