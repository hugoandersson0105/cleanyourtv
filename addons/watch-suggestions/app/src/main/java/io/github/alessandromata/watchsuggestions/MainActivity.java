package io.github.alessandromata.watchsuggestions;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * What to watch: trending movies and series with a muted trailer behind the title and synopsis; OK plays the trailer
 * with sound, in the TV's language when a dubbed one exists. "More" opens the full synopsis. The sources (Trakt
 * "most watched this week" + TMDB trending) are fetched again on every open and every 30 minutes.
 */
public class MainActivity extends Activity {
    private static final long REFRESH_MS = 30L * 60 * 1000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextureView tex;
    private Surface surface;
    private LinearLayout panel;
    private FrameLayout moreView;
    private ScrollView moreScroll;
    private TextView tvTitle, tvMeta, tvOverview, tvHint, tvStatus, moreTitle, moreBody;
    private Button btnWatch, btnMore;

    private List<Suggestions.Item> items;
    private int index = 0;
    private boolean full = false, moreOpen = false;
    private int gen = 0;              // invalidates a resolve/prepare for an item that already left the screen
    private int failures = 0;
    private long lastRefresh = 0;
    private Suggestions.Playable cur;
    private MediaPlayer vp, ap;
    private boolean videoPrepared = false;
    private final Runnable refreshTick = this::refresh;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Prefs.tmdbKey(this).isEmpty()) {            // first run: the owner brings their own TMDB key
            startActivity(new android.content.Intent(this, SettingsActivity.class));
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        showStatus(getString(R.string.loading));
        new Thread(() -> {
            List<Suggestions.Item> cached = Suggestions.cached(this);   // mostra algo na hora
            if (cached != null) ui.post(() -> onLoaded(cached));
        }).start();
        refresh();
    }

    /** Fetches Trakt + TMDB again; the list adjusts without interrupting whoever is watching. */
    private void refresh() {
        lastRefresh = System.currentTimeMillis();
        new Thread(() -> {
            List<Suggestions.Item> fresh = Suggestions.refresh(this);
            if (fresh != null) ui.post(() -> onLoaded(fresh));
        }).start();
        ui.removeCallbacks(refreshTick);
        ui.postDelayed(refreshTick, REFRESH_MS);
    }

    // ------------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        tex = new TextureView(this);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                if (vp != null) vp.setSurface(surface);
                else if (items != null) play();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (surface != null) surface.release();
                surface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        root.addView(tex, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0xB0000000, 0x00000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(48), 0, dp(48), dp(28));
        tvTitle = text(36, Color.WHITE, true);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        tvMeta = text(16, 0xFFB0B8C4, false);
        tvOverview = text(18, 0xFFE6E9EE, false);
        tvOverview.setMaxLines(3);
        tvOverview.setEllipsize(TextUtils.TruncateAt.END);
        tvOverview.setLineSpacing(0, 1.1f);

        btnWatch = pill(getString(R.string.watch_sound));
        btnWatch.setOnClickListener(v -> startFull());
        btnMore = pill(getString(R.string.more));
        btnMore.setOnClickListener(v -> openMore());
        tvHint = text(14, 0xFF8C95A3, false);
        tvHint.setText(R.string.hint);

        panel.addView(tvTitle);
        panel.addView(tvMeta, lp(-1, -2, 4, 0));
        panel.addView(tvOverview, lp(-1, -2, 8, 0));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(btnWatch);
        row.addView(btnMore, lp(-2, -2, 0, 12));
        Button btnSettings = pill(getString(R.string.settings));
        btnSettings.setOnClickListener(v -> startActivity(new android.content.Intent(this, SettingsActivity.class)));
        row.addView(btnSettings, lp(-2, -2, 0, 12));
        row.addView(tvHint, lp(-2, -2, 0, 20));
        panel.addView(row, lp(-1, -2, 12, 0));
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Sinopse completa
        moreView = new FrameLayout(this);
        moreView.setBackgroundColor(0xFA000000);
        moreView.setPadding(dp(64), dp(48), dp(64), dp(48));
        LinearLayout mc = new LinearLayout(this);
        mc.setOrientation(LinearLayout.VERTICAL);
        moreTitle = text(34, Color.WHITE, true);
        moreScroll = new ScrollView(this);
        moreBody = text(22, 0xFFE6E9EE, false);
        moreBody.setLineSpacing(0, 1.2f);
        moreScroll.addView(moreBody);
        TextView close = text(14, 0xFF8C95A3, false);
        close.setText(R.string.more_hint);
        mc.addView(moreTitle);
        mc.addView(moreScroll, lp(-1, 0, 16, 0));
        ((LinearLayout.LayoutParams) moreScroll.getLayoutParams()).weight = 1;
        mc.addView(close, lp(-2, -2, 12, 0));
        moreView.addView(mc, new FrameLayout.LayoutParams(-1, -1));
        moreView.setVisibility(View.GONE);
        root.addView(moreView, new FrameLayout.LayoutParams(-1, -1));

        tvStatus = text(24, Color.WHITE, false);
        root.addView(tvStatus, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
        btnWatch.requestFocus();
    }

    private Button pill(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(22), dp(6), dp(22), dp(6));
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFE50914);
        on.setCornerRadius(dp(24));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x55FFFFFF);
        off.setCornerRadius(dp(24));
        StateListDrawable sel = new StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_focused}, on);
        sel.addState(new int[]{}, off);
        b.setBackground(sel);
        return b;
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topDp, int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topDp);
        p.leftMargin = dp(leftDp);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showStatus(String msg) {
        tvStatus.setText(msg);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void openMore() {
        if (items == null || items.isEmpty()) return;
        Suggestions.Item it = items.get(index);
        moreTitle.setText(it.title + (it.year.isEmpty() ? "" : "  (" + it.year + ")"));
        moreBody.setText(it.overview);
        moreScroll.scrollTo(0, 0);
        moreView.setVisibility(View.VISIBLE);
        moreOpen = true;
    }

    private void closeMore() {
        moreView.setVisibility(View.GONE);
        moreOpen = false;
        btnMore.requestFocus();
    }

    // ----------------------------------------------------------------- lista

    private void onLoaded(List<Suggestions.Item> list) {
        if (list == null || list.isEmpty()) {
            if (items == null) showStatus(getString(R.string.no_data));
            return;
        }
        int keep = -1;                                  // keep the title on screen if it is still trending
        if (items != null && index < items.size()) {
            Suggestions.Item cur = items.get(index);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == cur.id && list.get(i).tv == cur.tv) keep = i;
            }
        }
        boolean first = items == null;
        items = list;
        tvStatus.setVisibility(View.GONE);
        if (full || moreOpen) { if (keep >= 0) index = keep; return; }   // never swap anything in the middle of a trailer or the synopsis
        panel.setVisibility(View.VISIBLE);
        if (first || keep < 0) show(0);
        else { index = keep; bind(); }
    }

    private void show(int i) {
        if (items == null || items.isEmpty()) return;
        index = (i % items.size() + items.size()) % items.size();
        bind();
        play();
    }

    private void bind() {
        Suggestions.Item it = items.get(index);
        tvTitle.setText(it.title);
        tvMeta.setText(String.format(Locale.US, "%s  •  %s  •  ★ %.1f  •  %d/%d",
                getString(it.tv ? R.string.series : R.string.movie), it.year, it.rating, index + 1, items.size()));
        tvOverview.setText(it.overview);
    }

    // ---------------------------------------------------------------- player

    private void release() {
        gen++;
        videoPrepared = false;
        cur = null;
        if (vp != null) { try { vp.release(); } catch (Exception ignored) {} vp = null; }
        if (ap != null) { try { ap.release(); } catch (Exception ignored) {} ap = null; }
    }

    /** Finds a trailer that plays for the current item (in the TV's language if one exists) and loops it muted. */
    private void play() {
        if (items == null || surface == null) return;
        release();
        final int my = gen;
        final Suggestions.Item it = items.get(index);
        new Thread(() -> {
            Suggestions.Playable p = Suggestions.findPlayable(this, it, false);
            ui.post(() -> {
                if (my != gen) return;
                if (p == null) { skip(); return; }
                cur = p;
                startVideo(p.video.getAbsolutePath(), my);
            });
        }).start();
    }

    private void startVideo(String path, int my) {
        try {
            vp = new MediaPlayer();
            vp.setDataSource(path);
            vp.setSurface(surface);
            vp.setVolume(0, 0);
            vp.setLooping(true);
            vp.setOnPreparedListener(m -> {
                if (my != gen) return;
                videoPrepared = true;
                failures = 0;
                m.start();
            });
            vp.setOnCompletionListener(m -> { if (full && my == gen) endFull(); });
            vp.setOnErrorListener((m, w, e) -> { android.util.Log.w("TelinhaTV", "video error " + w + "/" + e); if (my == gen) skip(); return true; });
            vp.prepareAsync();
        } catch (Exception e) {
            skip();
        }
    }

    /** A trailer that will not play (blocked, removed, network error): go to the next one, never loop forever. */
    private void skip() {
        if (items == null || ++failures > items.size()) { showStatus(getString(R.string.cant_play)); return; }
        if (full) { full = false; panel.setVisibility(View.VISIBLE); }
        show(index + 1);
    }

    /** The full trailer with sound: the one in the TV's language that the preview found, or a search for one; else the original. */
    private void startFull() {
        if (items == null || cur == null || !videoPrepared || full || moreOpen) return;
        full = true;
        panel.setVisibility(View.GONE);
        showStatus(getString(R.string.loading_trailer));
        final int my = gen;
        final Suggestions.Item it = items.get(index);
        final Suggestions.Playable base = cur;
        new Thread(() -> {
            Suggestions.Playable p = base.local ? base : Suggestions.findPlayable(this, it, true);
            final boolean dubbed = p != null;
            if (p == null) p = base;
            File audio = p.audioUrl == null ? null : Suggestions.download(this, p.audioUrl, p.key + ".m4a");
            final Suggestions.Playable fp = p;
            final File fa = audio;
            ui.post(() -> {
                if (my != gen || !full) return;
                tvStatus.setVisibility(View.GONE);
                if (fa == null) { Toast.makeText(this, R.string.no_audio, Toast.LENGTH_LONG).show(); endFull(); return; }
                if (!dubbed) Toast.makeText(this, R.string.no_dub, Toast.LENGTH_LONG).show();
                playFull(fp.video, fa, my);
            });
        }).start();
    }

    /** Video and audio (separate files) start together once both are ready. */
    private void playFull(File video, File audio, int my) {
        try {
            if (vp != null) { vp.release(); vp = null; }
            if (ap != null) { ap.release(); ap = null; }
            final int[] ready = {0};
            final Runnable go = () -> {
                if (++ready[0] < 2 || my != gen || !full) return;
                vp.seekTo(0);
                ap.seekTo(0);
                vp.start();
                ap.start();
            };
            vp = new MediaPlayer();
            vp.setDataSource(video.getAbsolutePath());
            vp.setSurface(surface);
            vp.setVolume(0, 0);
            vp.setLooping(false);
            vp.setOnPreparedListener(m -> go.run());
            vp.setOnCompletionListener(m -> { if (full && my == gen) endFull(); });
            vp.setOnErrorListener((m, w, e) -> { if (my == gen) endFull(); return true; });
            ap = new MediaPlayer();
            ap.setDataSource(audio.getAbsolutePath());
            ap.setOnPreparedListener(m -> go.run());
            ap.setOnErrorListener((m, w, e) -> { if (my == gen) endFull(); return true; });
            vp.prepareAsync();
            ap.prepareAsync();
        } catch (Exception e) {
            endFull();
        }
    }

    private void endFull() {
        full = false;
        panel.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.GONE);
        if (ap != null) { try { ap.release(); } catch (Exception ignored) {} ap = null; }
        if (vp != null) {
            try { vp.setLooping(true); vp.seekTo(0); vp.start(); } catch (Exception ignored) {}
        } else {
            play();
        }
        btnWatch.requestFocus();
    }

    private void togglePause() {
        if (vp == null) return;
        try {
            if (vp.isPlaying()) { vp.pause(); if (ap != null) ap.pause(); }
            else { vp.start(); if (ap != null) ap.start(); }
        } catch (Exception ignored) {
        }
    }

    // ----------------------------------------------------------------- teclas

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (moreOpen) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE || k == KeyEvent.KEYCODE_DPAD_CENTER
                    || k == KeyEvent.KEYCODE_ENTER) { closeMore(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) { moreScroll.smoothScrollBy(0, dp(120)); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_UP) { moreScroll.smoothScrollBy(0, -dp(120)); return true; }
            return true;
        }
        if (full) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) { endFull(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { togglePause(); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) { failures = 0; show(index + 1); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_UP) { failures = 0; show(index - 1); return true; }
        return super.dispatchKeyEvent(e);     // ◀ ▶ movem o foco entre "Assistir" e "Mais"
    }

    // -------------------------------------------------------------- ciclo de vida

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (System.currentTimeMillis() - lastRefresh > 10 * 60 * 1000) refresh();
        if (items != null && vp == null) play();
        if (items == null && !Prefs.tmdbKey(this).isEmpty() && System.currentTimeMillis() - lastRefresh > 5000) refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        full = false;
        panel.setVisibility(items == null ? View.GONE : View.VISIBLE);
        release();
        final android.content.Context c = getApplicationContext();
        new Thread(() -> Suggestions.clearTrailers(c)).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(refreshTick);
        release();
    }
}
