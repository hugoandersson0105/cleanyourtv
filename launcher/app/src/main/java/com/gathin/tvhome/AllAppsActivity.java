package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The All apps page (home_dock_only): the same landscape as Home, a "Back to clock" pill and the time at the top,
 * and every TV app in one frosted panel, the dock apps first in dock order, then the rest alphabetically. The
 * focused app's name shows at the panel's top right. Back or the pill returns to Home.
 *
 * Kept deliberately plain: no blur (the panel is a translucent fill), no animation beyond the focused tile's
 * scale, and banners load once when the page opens.
 */
public class AllAppsActivity extends Activity {

    private static final int INK = 0xFFFFFFFF;
    private float density;
    private TextView focusedName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        Config cfg = Config.load(this);          // also leaves the theme JSON in Config.overlay
        SceneConfig scene = SceneConfig.load(this);
        int columns = Math.max(4, Math.min(7, cfg.columns));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(cfg.backgroundColor);
        SkyView sky = new SkyView(this, null);
        root.addView(sky, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (!scene.scene.isEmpty()) {
            sky.setScene(scene.scene, scene.weatherOn ? scene.latitude : Double.NaN,
                    scene.weatherOn ? scene.longitude : Double.NaN);
            sky.setWeather(savedWeatherCode());
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int side = dp(48);
        page.setPadding(side, dp(30), side, dp(30));
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Top row: Back to clock on the left, the time on the right.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = pill(getString(R.string.back_to_clock));
        Drawable up = getDrawable(R.drawable.ic_chevron_down);
        if (up != null) {
            up = up.mutate();
            back.setCompoundDrawablesWithIntrinsicBounds(new RotatedDrawable(up), null, null, null);
            back.setCompoundDrawablePadding(dp(8));
        }
        back.setOnClickListener(v -> close());
        top.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(INK);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        clock.setTypeface(getResources().getFont(R.font.arimo_light));
        top.addView(clock);
        page.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // The panel.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x4D1C3550);
        glass.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        glass.setCornerRadius(dp(26));
        panel.setBackground(glass);
        panel.setPadding(dp(26), dp(18), dp(26), dp(10));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        plp.topMargin = dp(20);
        page.addView(panel, plp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.all_apps_title);
        title.setTextColor(INK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(getResources().getFont(R.font.arimo_medium));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        focusedName = new TextView(this);
        focusedName.setTextColor(0xD9FFFFFF);
        focusedName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        focusedName.setTypeface(getResources().getFont(R.font.arimo_regular));
        focusedName.setSingleLine(true);
        focusedName.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(focusedName);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(8);
        panel.addView(header, hlp);

        RecyclerView grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setPadding(0, dp(8), 0, dp(16));
        grid.setItemAnimator(null);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setAdapter(new Tiles(apps(cfg), columns));
        panel.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        panel.setClipChildren(false);

        setContentView(root);
        getWindow().setBackgroundDrawable(null);
        grid.post(() -> {
            RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
            if (first != null) first.itemView.requestFocus();
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            close();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void close() {
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** The weather code WeatherLine saved last, so this page's sky matches Home's. */
    private int savedWeatherCode() {
        SharedPreferences prefs = getSharedPreferences("weather", Context.MODE_PRIVATE);
        String json = prefs.getString("json", null);
        if (json == null) return -1;
        try {
            return new JSONObject(json).getJSONObject("current").optInt("weather_code", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Every TV app except this launcher and the theme's hidden apps: dock apps first, in dock order. */
    private List<App> apps(Config cfg) {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        Set<String> hidden = new HashSet<>(cfg.hiddenApps);
        Set<String> seen = new HashSet<>();
        List<App> dock = new ArrayList<>();
        List<App> rest = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            a.banner = banner;
            a.icon = banner == null ? ri.loadIcon(pm) : null;
            if (cfg.dockApps.contains(pkg)) dock.add(a); else rest.add(a);
        }
        Collections.sort(dock, (x, y) -> cfg.dockApps.indexOf(x.pkg) - cfg.dockApps.indexOf(y.pkg));
        Collator collator = Collator.getInstance();
        Collections.sort(rest, (x, y) -> collator.compare(x.label, y.label));
        dock.addAll(rest);
        return dock;
    }

    private void launch(App a) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (i == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private TextView pill(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(getResources().getFont(R.font.arimo_medium));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(18), 0, dp(22), 0);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(0x33FFFFFF);
        idle.setStroke(Math.max(1, dp(1)), 0x55FFFFFF);
        idle.setCornerRadius(dp(21));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFFFFFFFF);
        focused.setCornerRadius(dp(21));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, idle);
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, has) -> {
            int c = has ? 0xFF14202E : INK;
            t.setTextColor(c);
            for (Drawable d : t.getCompoundDrawables()) if (d != null) d.setTint(c);
        });
        t.setTextColor(INK);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static final class App {
        String pkg;
        String label;
        Drawable banner;
        Drawable icon;
    }

    /** A 16:9 tile: the app's banner, or its icon and name on a dark card. Focus scales it up a little. */
    private final class Tiles extends RecyclerView.Adapter<Tiles.Holder> {
        private final List<App> apps;
        private final int columns;

        Tiles(List<App> apps, int columns) {
            this.apps = apps;
            this.columns = columns;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView name;

            Holder(FrameLayout v, ImageView image, TextView name) {
                super(v);
                this.image = image;
                this.name = name;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int gap = dp(16);
            int width = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / columns;
            int tileW = Math.max(dp(80), width - gap);
            int tileH = Math.round(tileW * 9f / 16f);
            FrameLayout cell = new FrameLayout(AllAppsActivity.this);
            cell.setClipChildren(false);
            FrameLayout tile = new FrameLayout(AllAppsActivity.this);
            GradientDrawable card = new GradientDrawable();
            card.setColor(0xFF1E2633);
            card.setCornerRadius(dp(12));
            tile.setBackground(card);
            tile.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            tile.setClipToOutline(true);
            ImageView image = new ImageView(AllAppsActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            TextView name = new TextView(AllAppsActivity.this);
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setTypeface(getResources().getFont(R.font.arimo_regular));
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            nlp.bottomMargin = dp(8);
            tile.addView(name, nlp);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(tileW, tileH, Gravity.CENTER);
            cell.addView(tile, tlp);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH + gap));
            cell.setFocusable(true);
            cell.setClickable(true);
            Holder h = new Holder(cell, image, name);
            cell.setOnFocusChangeListener((v, has) -> {
                float s = has ? 1.08f : 1f;
                tile.animate().scaleX(s).scaleY(s).setDuration(140).start();
                tile.setElevation(has ? dp(10) : 0);
                if (has) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) focusedName.setText(apps.get(pos).label);
                }
            });
            cell.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) launch(apps.get(pos));
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            App a = apps.get(position);
            if (a.banner != null) {
                h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.image.setPadding(0, 0, 0, 0);
                h.image.setImageDrawable(a.banner);
                h.name.setVisibility(View.GONE);
            } else {
                h.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int p = dp(18);
                h.image.setPadding(p, dp(10), p, dp(30));
                h.image.setImageDrawable(a.icon);
                h.name.setText(a.label);
                h.name.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }

    /** The chevron drawable turned upside down, for "Back to clock". */
    private static final class RotatedDrawable extends android.graphics.drawable.LayerDrawable {
        RotatedDrawable(Drawable d) {
            super(new Drawable[]{d});
        }

        @Override
        public void draw(@NonNull android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            canvas.save();
            canvas.rotate(180f, b.exactCenterX(), b.exactCenterY());
            super.draw(canvas);
            canvas.restore();
        }
    }
}
