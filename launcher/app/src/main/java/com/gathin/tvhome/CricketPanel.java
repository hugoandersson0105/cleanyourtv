package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * The score lines under the clock. Drawing uses the last good cache; the network runs on one
 * background thread, and only while Home is resumed, focused, awake and online.
 */
final class CricketPanel {
    static final String ENABLED = "cricket_enabled";
    static final String TEAMS = "cricket_teams";
    static final String STATUS = "cricket_status";
    private static final String BACKOFF_UNTIL = "cricket_backoff_until";
    private static final String BACKOFF_MS = "cricket_backoff_ms";
    private static final String BUDGET = "cricket-budget.txt";

    private final Activity activity;
    private final View root;
    private final TextView scores;
    private final ImageView staleIcon;
    private final TextView staleTime;
    private final DateTimeFormatter stamp = DateTimeFormatter.ofPattern("d MMM, h:mm a");

    private volatile boolean resumed;
    private volatile boolean focused;
    private HandlerThread thread;
    private volatile Handler bg;

    CricketPanel(Activity activity) {
        this.activity = activity;
        root = activity.findViewById(R.id.cricket);
        scores = activity.findViewById(R.id.cricketScores);
        staleIcon = activity.findViewById(R.id.cricketStale);
        staleTime = activity.findViewById(R.id.cricketStaleTime);
    }

    void onResume() {
        resumed = true;
        kick();
    }

    void onPause() {
        resumed = false;
        stop();
    }

    void onWindowFocus(boolean hasFocus) {
        focused = hasFocus;
        if (hasFocus) kick();
        else stop();
    }

    private void kick() {
        if (root == null || !resumed || !focused) return;
        if (thread != null) return;
        thread = new HandlerThread("cricket");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        bg = handler;
        handler.post(this::cycle);
    }

    private void stop() {
        Handler handler = bg;
        bg = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) {
            thread.quit();
            thread = null;
        }
    }

    private void cycle() {
        Handler handler = bg;
        if (handler == null) return;
        CricketSecret.importExternal(activity);
        SharedPreferences prefs = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(ENABLED, false);
        List<String> teams = CricketTeams.load(prefs.contains(TEAMS) ? prefs.getString(TEAMS, "") : null);
        CricketSnapshot snap = CricketSnapshot.read(activity);
        activity.runOnUiThread(() -> {
            if (bg == handler) show(enabled, teams, snap);
        });
        if (!enabled || !CricketSecret.exists(activity)) return;
        if (!CricketPolicy.canFetch(resumed, focused, interactive(), online())) {
            if (resumed && focused) again(handler, 30_000);
            return;
        }
        long now = System.currentTimeMillis();
        boolean live = snap != null && CricketBoard.anyLive(
                CricketBoard.choose(snap.matches, teams, CricketBoard.LIMIT));
        long wait = CricketPolicy.waitMs(now, snap == null ? 0 : snap.fetchedAt, live,
                prefs.getLong(BACKOFF_UNTIL, 0));
        if (wait > 0) {
            again(handler, Math.min(wait, 60_000));
            return;
        }
        String day = LocalDate.now(ZoneId.systemDefault()).toString();
        String budget = readBudget();
        if (!CricketBudget.allow(budget, day)) {
            again(handler, 30 * 60 * 1000L);
            return;
        }
        try {
            saveBudget(CricketBudget.hit(budget, day));
            String key = CricketSecret.get(activity);
            if (key.isEmpty()) return;
            String body = CricketClient.fetch(key);
            CricketFeed feed = CricketFeed.parse(body, now, ZoneId.systemDefault());
            saveBudget(CricketBudget.observe(readBudget(), day, feed.hitsToday));
            CricketSnapshot next = new CricketSnapshot(now, feed.matches);
            next.write(activity);
            prefs.edit().putLong(BACKOFF_UNTIL, 0).putLong(BACKOFF_MS, 0).apply();
            activity.runOnUiThread(() -> {
                if (bg == handler) show(enabled, teams, next);
            });
            boolean shownLive = CricketBoard.anyLive(
                    CricketBoard.choose(feed.matches, teams, CricketBoard.LIMIT));
            again(handler, CricketPolicy.interval(shownLive));
        } catch (Exception e) {
            long nextWait = CricketPolicy.nextBackoff(prefs.getLong(BACKOFF_MS, 0));
            prefs.edit().putLong(BACKOFF_MS, nextWait)
                    .putLong(BACKOFF_UNTIL, System.currentTimeMillis() + nextWait).apply();
            CricketSnapshot kept = CricketSnapshot.read(activity);
            activity.runOnUiThread(() -> {
                if (bg == handler) show(enabled, teams, kept);
            });
            again(handler, nextWait);
        }
    }

    /** Schedules the next look, unless Home has since left the screen. */
    private void again(Handler handler, long delay) {
        if (bg != handler) return;
        handler.postDelayed(this::cycle, delay);
        if (bg != handler) handler.removeCallbacksAndMessages(null);
    }

    private void show(boolean enabled, List<String> teams, CricketSnapshot snap) {
        if (root == null || activity.isDestroyed()) return;
        if (!enabled) {
            root.setVisibility(View.GONE);
            writeStatus(activity.getString(R.string.cricket_status_off));
            return;
        }
        if (!CricketSecret.exists(activity)) {
            root.setVisibility(View.GONE);
            writeStatus(activity.getString(R.string.cricket_status_needs_key));
            return;
        }
        List<CricketMatch> chosen = snap == null ? Collections.emptyList()
                : CricketBoard.choose(snap.matches, teams, CricketBoard.LIMIT);
        boolean live = CricketBoard.anyLive(chosen);
        boolean stale = snap != null && CricketPolicy.stale(System.currentTimeMillis(), snap.fetchedAt, live);
        if (chosen.isEmpty()) {
            root.setVisibility(View.GONE);
            writeStatus(snap == null
                    ? activity.getString(R.string.cricket_status_waiting)
                    : activity.getString(R.string.cricket_status_none, when(snap.fetchedAt)));
        } else {
            scores.setText(CricketBoard.block(chosen));
            root.setVisibility(View.VISIBLE);
            writeStatus(stale
                    ? activity.getString(R.string.cricket_status_stale, when(snap.fetchedAt))
                    : activity.getString(R.string.cricket_status_updated, when(snap.fetchedAt)));
        }
        if (!stale || snap == null) {
            staleIcon.setOnFocusChangeListener(null);
            staleIcon.setFocusable(false);
            staleIcon.setVisibility(View.GONE);
            staleTime.setVisibility(View.GONE);
            return;
        }
        String when = when(snap.fetchedAt);
        staleTime.setText(when);
        staleTime.setVisibility(View.GONE);
        staleIcon.setVisibility(View.VISIBLE);
        staleIcon.setFocusable(true);
        staleIcon.setContentDescription(activity.getString(R.string.cricket_stale));
        staleIcon.setOnFocusChangeListener((view, hasFocus) -> {
            staleTime.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
            staleIcon.setContentDescription(hasFocus
                    ? activity.getString(R.string.cricket_updated, when)
                    : activity.getString(R.string.cricket_stale));
        });
    }

    private String when(long fetchedAt) {
        return stamp.format(Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()));
    }

    private void writeStatus(String status) {
        SharedPreferences prefs = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        if (status.equals(prefs.getString(STATUS, ""))) return;
        prefs.edit().putString(STATUS, status).apply();
    }

    private boolean interactive() {
        PowerManager power = activity.getSystemService(PowerManager.class);
        return power != null && power.isInteractive();
    }

    private boolean online() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private String readBudget() {
        File file = new File(activity.getFilesDir(), BUDGET);
        if (!file.isFile() || file.length() > 64) return "";
        try {
            byte[] bytes = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            try {
                int got = 0;
                while (got < bytes.length) {
                    int n = in.read(bytes, got, bytes.length - got);
                    if (n < 0) break;
                    got += n;
                }
            } finally {
                in.close();
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void saveBudget(String value) throws IOException {
        File dest = new File(activity.getFilesDir(), BUDGET);
        File tmp = new File(activity.getFilesDir(), BUDGET + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IOException("cricket budget was not saved");
        }
    }
}
