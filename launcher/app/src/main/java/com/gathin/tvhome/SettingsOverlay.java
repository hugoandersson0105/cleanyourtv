package com.gathin.tvhome;

// Ported from the "Clean TV for TCL G10" fork, github.com/itsmevizz/cleanyourtv (branch clean-tv-launcher, MIT),
// for the Control Center panel (control_center). Changed here: resolveOne() picks a single settings page.

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.Inet4Address;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Control Center style panel, opened by the remote's Settings button from any screen. It is drawn as
 * an accessibility overlay by HomeKeyService, so the app underneath keeps running. Shows the time, the
 * network, storage and memory, the settings pages as tiles, and the TV's notifications. Up, Down, Left and
 * Right move, OK opens a tile or notification, Back or the Settings button closes it.
 */
final class SettingsOverlay {

    /** The TV's own input switcher. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private static final long CLOCK_TICK_MS = 15_000L;
    private static final long TRAFFIC_TICK_MS = 1_000L;
    /** Daily usage is written this many samples apart, not every second. */
    private static final int SAVE_EVERY = 10;
    private static final String USAGE_PREFS = "network_usage";
    private static final int MAX_SHOWN_NOTIFICATIONS = 6;

    private final Context app;
    private final Context themed;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View root;
    private LinearLayout panel, netSlot, infoRow, tileGrid, notifications;
    private TextView clock, ampm, date, noNotes, footer;
    private View netTile;
    private TextView netTitle, netSubtitle, speedDown, speedUp, usageText, linkText;
    private NetworkGraphView graph;
    private BluetoothInfo bluetooth;
    private LinearLayout btSlot, btDevices;
    /** Battery reads hold a short connection to the remote: once when the panel opens, then every 5 minutes. */
    private static final long BLUETOOTH_EVERY_MS = 5 * 60_000L;
    /** Counters from the last sample, and when it was taken; -1 until the first one. */
    private long lastRx = -1, lastTx = -1, lastSampleMs;
    /** Bytes used today, from the counters, kept across panel openings. */
    private long dayRx, dayTx;
    private String dayKey = "";
    private int samplesSinceSave;
    private TextView storageValue, memValue;
    private ProgressBar storageBar, memBar;
    private boolean showing;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, CLOCK_TICK_MS);
        }
    };

    private final Runnable bluetoothTick = new Runnable() {
        @Override
        public void run() {
            refreshBluetooth();
            handler.postDelayed(this, BLUETOOTH_EVERY_MS);
        }
    };

    /** Once a second while the panel is open: the speed for the chart, and the day's usage. */
    private final Runnable traffic = new Runnable() {
        @Override
        public void run() {
            sampleTraffic();
            handler.postDelayed(this, TRAFFIC_TICK_MS);
        }
    };

    SettingsOverlay(Context service) {
        app = service;
        themed = new ContextThemeWrapper(service, R.style.Theme_TvHome);
        wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() {
        return showing;
    }

    void toggle() {
        if (showing) hide();
        else show();
    }

    void show() {
        if (showing || wm == null) return;
        if (root == null) build();
        refresh();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("Control Center");
        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) {
            return;   // the overlay window is not allowed right now
        }
        showing = true;
        loadUsage();
        graph.clear();
        handler.removeCallbacks(bluetoothTick);
        handler.post(bluetoothTick);
        lastRx = -1;
        lastTx = -1;
        handler.removeCallbacks(tick);
        handler.post(tick);
        handler.removeCallbacks(traffic);
        handler.post(traffic);
        float w = 340 * app.getResources().getDisplayMetrics().density;
        panel.setTranslationX(w);
        panel.animate().translationX(0f).setDuration(220).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        root.post(() -> {
            View first = netTile;
            if (first != null) first.requestFocus();
        });
    }

    void hide() {
        if (!showing) return;
        showing = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(traffic);
        handler.removeCallbacks(bluetoothTick);
        saveUsage();
        try {
            wm.removeView(root);
        } catch (RuntimeException ignored) {
        }
    }

    // ---- building ---------------------------------------------------------------------------------

    private void build() {
        LayoutInflater inflater = LayoutInflater.from(themed);
        root = inflater.inflate(R.layout.settings_overlay, null);
        panel = root.findViewById(R.id.panel);
        netSlot = root.findViewById(R.id.netSlot);
        infoRow = root.findViewById(R.id.infoRow);
        tileGrid = root.findViewById(R.id.tileGrid);
        notifications = root.findViewById(R.id.notifications);
        clock = root.findViewById(R.id.clock);
        ampm = root.findViewById(R.id.ampm);
        date = root.findViewById(R.id.date);
        noNotes = root.findViewById(R.id.noNotes);
        footer = root.findViewById(R.id.footer);

        // A click outside the card closes the panel; a click on the card does nothing.
        root.setOnClickListener(v -> hide());
        panel.setClickable(true);
        root.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                hide();
                return true;
            }
            return false;
        });

        // Network: the Wi-Fi card, with the speed chart behind its text.
        netTile = inflater.inflate(R.layout.item_network_card, netSlot, false);
        netTitle = netTile.findViewById(R.id.netTitle);
        netSubtitle = netTile.findViewById(R.id.netSubtitle);
        graph = netTile.findViewById(R.id.graph);
        speedDown = netTile.findViewById(R.id.speedDown);
        speedUp = netTile.findViewById(R.id.speedUp);
        usageText = netTile.findViewById(R.id.usageText);
        linkText = netTile.findViewById(R.id.linkText);
        netTile.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        netTile.setOnKeyListener(this::backCloses);
        netSlot.addView(netTile);

        // Storage and memory: information only, not focusable.
        View storage = inflater.inflate(R.layout.item_control_info, infoRow, false);
        ((TextView) storage.findViewById(R.id.title)).setText(R.string.panel_storage_short);
        storageValue = storage.findViewById(R.id.value);
        storageBar = storage.findViewById(R.id.bar);
        infoRow.addView(storage);
        View memory = inflater.inflate(R.layout.item_control_info, infoRow, false);
        ((TextView) memory.findViewById(R.id.title)).setText(R.string.panel_memory_short);
        memValue = memory.findViewById(R.id.value);
        memBar = memory.findViewById(R.id.bar);
        infoRow.addView(memory);

        // Bluetooth: the paired devices and the remote's battery.
        bluetooth = new BluetoothInfo(app);
        btSlot = root.findViewById(R.id.btSlot);
        View btCard = inflater.inflate(R.layout.item_bluetooth_card, btSlot, false);
        btDevices = btCard.findViewById(R.id.btDevices);
        btCard.setOnClickListener(v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        btCard.setOnKeyListener(this::backCloses);
        btSlot.addView(btCard);

        // Settings tiles, two to a row.
        List<Object[]> tiles = java.util.Arrays.asList(
                new Object[]{R.drawable.ic_input, R.string.inputs, new Intent(ACTION_VIEW_INPUTS)},
                new Object[]{R.drawable.ic_av, R.string.panel_tile_sound, new Intent(Settings.ACTION_SOUND_SETTINGS)},
                new Object[]{R.drawable.ic_livetv, R.string.panel_tile_display, new Intent(Settings.ACTION_DISPLAY_SETTINGS)},
                new Object[]{R.drawable.ic_home_settings, R.string.panel_tile_apps, new Intent(Settings.ACTION_APPLICATION_SETTINGS)},
                new Object[]{R.drawable.ic_lock, R.string.panel_tile_accessibility, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)},
                new Object[]{R.drawable.ic_settings, R.string.panel_tile_all, new Intent(Settings.ACTION_SETTINGS)});
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            Object[] t = tiles.get(i);
            if (i % 2 == 0) {
                row = new LinearLayout(themed);
                row.setOrientation(LinearLayout.HORIZONTAL);
                tileGrid.addView(row);
            }
            View tile = inflater.inflate(R.layout.item_control_tile, row, false);
            ((ImageView) tile.findViewById(R.id.icon)).setImageResource((Integer) t[0]);
            ((TextView) tile.findViewById(R.id.title)).setText((Integer) t[1]);
            tile.findViewById(R.id.subtitle).setVisibility(View.GONE);
            // A gap of 5dp on each side of every tile: 10dp between tiles, and the same at the panel's edges.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            float density = app.getResources().getDisplayMetrics().density;
            int gap = (int) (5 * density);
            lp.setMargins(gap, 0, gap, (int) (10 * density));   // 10dp below each tile, as between the rows
            tile.setLayoutParams(lp);
            Intent intent = (Intent) t[2];
            tile.setOnClickListener(v -> open(intent));
            tile.setOnKeyListener(this::backCloses);
            row.addView(tile);
        }

        root.setVisibility(View.VISIBLE);
    }

    private boolean backCloses(View v, int code, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK) {
            hide();
            return true;
        }
        return false;
    }

    /** The TV's own settings app: preferred when several activities answer a settings action. */
    private static final String TV_SETTINGS = "com.android.tv.settings";

    /**
     * One page, never Android's "Complete action using" chooser: on TCL Google TV two TV Settings activities answer
     * APPLICATION_SETTINGS and a framework stub also answers ACCESSIBILITY_SETTINGS, so the first TV Settings match
     * (they come in priority order) is picked. BLUETOOTH_SETTINGS has no handler there at all, so it opens TV
     * Settings' Remotes & Accessories page, or TV Settings itself.
     */
    private Intent resolveOne(Intent i) {
        if (i.getComponent() != null || i.getPackage() != null) return i;
        android.content.pm.PackageManager pm = app.getPackageManager();
        List<android.content.pm.ResolveInfo> all =
                pm.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if (all.isEmpty()) {
            if (Settings.ACTION_BLUETOOTH_SETTINGS.equals(i.getAction())) {
                Intent accessories = new Intent().setClassName(TV_SETTINGS,
                        TV_SETTINGS + ".accessories.AccessoriesActivity");
                if (pm.resolveActivity(accessories, 0) != null) return accessories;
                return new Intent(Settings.ACTION_SETTINGS).setPackage(TV_SETTINGS);
            }
            return i;
        }
        if (all.size() == 1) return i;
        android.content.pm.ResolveInfo pick = all.get(0);
        for (android.content.pm.ResolveInfo ri : all) {
            if (ri.activityInfo != null && TV_SETTINGS.equals(ri.activityInfo.packageName)) {
                pick = ri;
                break;
            }
        }
        return new Intent(i).setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
    }

    private void open(Intent i) {
        hide();
        i = resolveOne(i);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            // this build has no such page; the panel is closed either way
        }
    }

    // ---- live information -------------------------------------------------------------------------

    private void refreshBluetooth() {
        bluetooth.refresh(this::renderBluetooth);
    }

    /** Only connected devices are listed. With none connected, or Bluetooth off, the card is hidden. */
    private void renderBluetooth(List<BluetoothInfo.Device> devices) {
        if (!showing || btDevices == null) return;
        btDevices.removeAllViews();
        List<BluetoothInfo.Device> connected = new java.util.ArrayList<>();
        if (devices != null && bluetooth.hasPermission()) {
            for (BluetoothInfo.Device d : devices) if (d.connected) connected.add(d);
        }
        btSlot.setVisibility(connected.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(themed);
        for (BluetoothInfo.Device d : connected) {
            View row = inflater.inflate(R.layout.item_bt_device, btDevices, false);
            ((TextView) row.findViewById(R.id.name)).setText(d.name);
            ((TextView) row.findViewById(R.id.state)).setText(R.string.panel_bt_connected);
            TextView battery = row.findViewById(R.id.battery);
            ProgressBar bar = row.findViewById(R.id.bar);
            if (d.battery >= 0) {
                battery.setText(app.getString(R.string.panel_bt_battery, d.battery));
                bar.setProgress(d.battery);
            } else {
                battery.setText("—");
                bar.setProgress(0);
            }
            btDevices.addView(row);
        }
    }

    private void refresh() {
        updateClock();
        updateNetwork();
        updateLink();
        updateUsage();
        updateStorage();
        updateMemory();
        updateNotifications();
        footer.setText(app.getString(R.string.panel_footer, Build.MODEL, Build.VERSION.RELEASE));
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        ampm.setText(new SimpleDateFormat("a", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now));
    }

    private void updateNetwork() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        Network active = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = active != null && cm != null ? cm.getNetworkCapabilities(active) : null;
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            netTitle.setText(R.string.panel_offline);
            netSubtitle.setText(R.string.side_network);
            return;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            netTitle.setText(R.string.ethernet);
            netSubtitle.setText("");
            return;
        }
        WifiManager wifi = (WifiManager) app.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
        String ssid = info != null && info.getSSID() != null ? info.getSSID().replace("\"", "").trim() : "";
        if (ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x")) ssid = app.getString(R.string.wifi);
        netTitle.setText(ssid);
        String signal = info != null ? signalWord(WifiManager.calculateSignalLevel(info.getRssi(), 4)) : "";
        netSubtitle.setText(signal);
    }

    /** Link quality: the negotiated speed and the signal strength in dBm (Android 10 and later report the speed). */
    private void updateLink() {
        WifiManager wifi = (WifiManager) app.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
        if (info == null || info.getNetworkId() < 0) {
            linkText.setText(R.string.panel_link_unknown);
            return;
        }
        int rssi = info.getRssi();
        int mbps = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? info.getRxLinkSpeedMbps() : 0;
        if (mbps <= 0) {
            linkText.setText(app.getString(R.string.panel_signal_dbm, rssi));
        } else {
            linkText.setText(app.getString(R.string.panel_link, mbps, rssi));
        }
    }

    /** One sample: the bytes moved since the last one, as a speed for the chart, and added to today's usage. */
    private void sampleTraffic() {
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED || rx < 0 || tx < 0) {
            speedDown.setText(R.string.panel_no_traffic);
            speedUp.setText("");
            return;
        }
        if (lastRx >= 0 && now > lastSampleMs) {
            double seconds = (now - lastSampleMs) / 1000.0;
            long dRx = Math.max(0, rx - lastRx), dTx = Math.max(0, tx - lastTx);
            float downSpeed = (float) (dRx / seconds), upSpeed = (float) (dTx / seconds);
            graph.push(downSpeed, upSpeed);
            speedDown.setText(app.getString(R.string.panel_speed_down, Formatter.formatShortFileSize(app, (long) downSpeed)));
            speedUp.setText(app.getString(R.string.panel_speed_up, Formatter.formatShortFileSize(app, (long) upSpeed)));
            dayRx += dRx;
            dayTx += dTx;
            updateUsage();
            if (++samplesSinceSave >= SAVE_EVERY) saveUsage();
        }
        lastRx = rx;
        lastTx = tx;
        lastSampleMs = now;
        updateLink();
    }

    private void updateUsage() {
        usageText.setText(app.getString(R.string.panel_usage_today,
                Formatter.formatShortFileSize(app, dayRx), Formatter.formatShortFileSize(app, dayTx)));
    }

    /** Today's usage survives closing the panel and the launcher; a new day starts from zero. */
    private void loadUsage() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        SharedPreferences prefs = app.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE);
        if (today.equals(prefs.getString("day", ""))) {
            dayRx = prefs.getLong("rx", 0);
            dayTx = prefs.getLong("tx", 0);
        } else {
            dayRx = 0;
            dayTx = 0;
        }
        dayKey = today;
    }

    private void saveUsage() {
        samplesSinceSave = 0;
        if (dayKey.isEmpty()) return;
        app.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("day", dayKey).putLong("rx", dayRx).putLong("tx", dayTx).apply();
    }

    private String signalWord(int level) {
        switch (level) {
            case 3: return app.getString(R.string.panel_signal_excellent);
            case 2: return app.getString(R.string.panel_signal_good);
            case 1: return app.getString(R.string.panel_signal_fair);
            default: return app.getString(R.string.panel_signal_weak);
        }
    }

    private String ipAddress(ConnectivityManager cm, Network network) {
        if (cm == null || network == null) return "";
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) return "";
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return "";
    }

    private void updateStorage() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
            storageValue.setText(app.getString(R.string.panel_free_short, Formatter.formatShortFileSize(app, free)));
            storageBar.setProgress((int) (1000L * (total - free) / Math.max(1, total)));
        } catch (RuntimeException e) {
            storageValue.setText("");
        }
    }

    private void updateMemory() {
        ActivityManager am = app.getSystemService(ActivityManager.class);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        memValue.setText(app.getString(R.string.panel_free_short, Formatter.formatShortFileSize(app, mi.availMem)));
        memBar.setProgress((int) (1000L * (mi.totalMem - mi.availMem) / Math.max(1, mi.totalMem)));
    }

    private void updateNotifications() {
        notifications.removeAllViews();
        List<NotificationService.Item> items = NotificationService.snapshot();
        noNotes.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(themed);
        for (int i = 0; i < items.size() && i < MAX_SHOWN_NOTIFICATIONS; i++) {
            NotificationService.Item item = items.get(i);
            View row = inflater.inflate(R.layout.item_notification, notifications, false);
            ImageView icon = row.findViewById(R.id.icon);
            try {
                Drawable d = app.getPackageManager().getApplicationIcon(item.pkg);
                icon.setImageDrawable(d);
            } catch (Exception e) {
                icon.setImageResource(R.drawable.ic_av);
            }
            ((TextView) row.findViewById(R.id.title)).setText(item.title != null ? item.title : "");
            ((TextView) row.findViewById(R.id.text)).setText(item.text != null ? item.text : "");
            row.setOnClickListener(v -> openNotification(item));
            row.setOnKeyListener(this::backCloses);
            notifications.addView(row);
        }
    }

    /** OK on a notification: open the app that posted it, the same way its own tap would. */
    private void openNotification(NotificationService.Item item) {
        hide();
        if (item.open == null) return;
        try {
            item.open.send();
        } catch (PendingIntent.CanceledException e) {
            // the app removed its intent; nothing to open
        }
    }
}
