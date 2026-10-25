package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Options for the night scene, the large clock and the weather line: config.xml first, then the pushed
 * theme's config.json (Config.overlay), the same order Config uses. Kept apart from Config so these keys
 * do not grow its constructor further.
 *
 * <pre>
 * "scene": "night"              still night landscape behind Home (NightScene); "" keeps the plain background
 * "clock_size": 120             clock text size in sp; 0 keeps the built-in 52
 * "clock_style": "large"        date on its own line in sentence case ("Sunday, 25 October"), weather under it
 * "weather_city": "Lisbon"      label before the temperature; empty hides the city name
 * "weather_latitude": 38.72     place to ask Open-Meteo about; both empty switches the weather line off
 * "weather_longitude": -9.14
 * "weather_unit": "c"           c or f
 * "weather_forecast": false     leave out the hourly and daily lines under the weather line
 * "input_extra": ["AirPlay=com.amlogic.airplay/.luna.LunaActivity",
 *                 "Screen mirroring=com.tcl.miracast#com.tcl.miracast.test.MAINMENU"]
 *                               extra rows at the end of the Inputs menu
 * "home_dock_only": true        Home shows only the dock apps; Down from the dock opens the All apps page
 * "dock_bottom_dp": 6           gap under the dock in dp (default 30)
 * "control_center": true        the remote's Settings button opens the Control Center panel (SettingsOverlay)
 * </pre>
 */
final class SceneConfig {

    final String scene;
    final int clockSize;
    final boolean largeClock;
    final String weatherCity;
    final double latitude;
    final double longitude;
    final boolean weatherOn;
    final boolean fahrenheit;
    final List<String> inputExtra;
    final boolean homeDockOnly;
    final boolean controlCenter;
    final boolean showForecast;
    final int dockBottomDp;

    private SceneConfig(String scene, int clockSize, boolean largeClock, String weatherCity, double latitude,
                        double longitude, boolean weatherOn, boolean fahrenheit, List<String> inputExtra,
                        boolean homeDockOnly, int dockBottomDp, boolean controlCenter,
                        boolean showForecast) {
        this.controlCenter = controlCenter;
        this.showForecast = showForecast;
        this.inputExtra = inputExtra;
        this.homeDockOnly = homeDockOnly;
        this.dockBottomDp = dockBottomDp;
        this.scene = scene;
        this.clockSize = clockSize;
        this.largeClock = largeClock;
        this.weatherCity = weatherCity;
        this.latitude = latitude;
        this.longitude = longitude;
        this.weatherOn = weatherOn;
        this.fahrenheit = fahrenheit;
    }

    /** Call after Config.load, which leaves the theme's JSON in Config.overlay. */
    static SceneConfig load(Context ctx) {
        Resources res = ctx.getResources();
        String scene = res.getString(R.string.scene).trim();
        int clockSize = res.getInteger(R.integer.clock_size);
        String clockStyle = res.getString(R.string.clock_style).trim();
        String city = res.getString(R.string.weather_city).trim();
        String lat = res.getString(R.string.weather_latitude).trim();
        String lon = res.getString(R.string.weather_longitude).trim();
        String unit = res.getString(R.string.weather_unit).trim();
        List<String> extra = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.input_extra)));
        boolean dockOnly = res.getBoolean(R.bool.home_dock_only);
        boolean control = res.getBoolean(R.bool.control_center);
        boolean forecast = res.getBoolean(R.bool.weather_forecast);
        int dockBottom = res.getInteger(R.integer.dock_bottom_dp);

        JSONObject json = Config.overlay;
        if (json != null) {
            scene = str(json, "scene", scene);
            Object size = json.opt("clock_size");
            if (size instanceof Number) clockSize = ((Number) size).intValue();
            clockStyle = str(json, "clock_style", clockStyle);
            city = str(json, "weather_city", city);
            lat = num(json, "weather_latitude", lat);
            lon = num(json, "weather_longitude", lon);
            unit = str(json, "weather_unit", unit);
            Object fc = json.opt("weather_forecast");
            if (fc instanceof Boolean) forecast = (Boolean) fc;
            Object cc = json.opt("control_center");
            if (cc instanceof Boolean) control = (Boolean) cc;
            Object only = json.opt("home_dock_only");
            if (only instanceof Boolean) dockOnly = (Boolean) only;
            Object bottom = json.opt("dock_bottom_dp");
            if (bottom instanceof Number) dockBottom = ((Number) bottom).intValue();
            JSONArray rows = json.optJSONArray("input_extra");
            if (rows != null) {
                extra = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    String row = rows.optString(i, "").trim();
                    if (!row.isEmpty()) extra.add(row);
                }
            }
        }
        if (clockSize != 0 && (clockSize < 24 || clockSize > 220)) clockSize = 0;

        double la = Double.NaN;
        double lo = Double.NaN;
        try {
            if (!lat.isEmpty() && !lon.isEmpty()) {
                la = Double.parseDouble(lat);
                lo = Double.parseDouble(lon);
            }
        } catch (NumberFormatException ignored) {
            la = Double.NaN;
        }
        boolean weatherOn = !Double.isNaN(la) && !Double.isNaN(lo)
                && Math.abs(la) <= 90 && Math.abs(lo) <= 180;
        return new SceneConfig(scene, clockSize, "large".equalsIgnoreCase(clockStyle), city, la, lo, weatherOn,
                "f".equalsIgnoreCase(unit), Collections.unmodifiableList(extra),
                dockOnly, dockBottom < 0 || dockBottom > 200 ? -1 : dockBottom, control,
                forecast);
    }

    boolean nightScene() {
        return "night".equalsIgnoreCase(scene);
    }

    private static String str(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    /** A number or a numeric string, as text. */
    private static String num(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        if (v instanceof Number) return v.toString();
        if (v instanceof String) return ((String) v).trim();
        return fallback;
    }
}
