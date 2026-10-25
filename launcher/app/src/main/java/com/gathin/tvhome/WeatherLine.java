package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * The weather under the date, from Open-Meteo (no key, no account):
 * <pre>
 * Lisbon · 21° Partly cloudy · H 23°  L 15° · Feels like 21°
 * 3 PM 30°   6 PM 28°   9 PM 27°   12 AM 27°
 * Mon 31° / 25°   Tue 30° / 25°   Wed 31° / 26°
 * </pre>
 * and the current conditions as a WMO code for the scene behind Home.
 *
 * It asks only while Home is on screen (start in onResume, stop in onPause): when Home comes back and the last
 * answer is 30 minutes old, then every 30 minutes, or 5 minutes after a failure. Only the configured latitude
 * and longitude are sent. The last answer is kept, so Home shows it at once; one older than three hours is hidden
 * rather than shown as current. The two lines are only ever made invisible, never gone: the grid sits under the
 * header, and a header that grew when the first reading arrived would leave the dock parked off its line.
 */
final class WeatherLine {
    private static final String TAG = "TvHome.Weather";
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final long STALE_MS = 3 * 60 * 60_000L;

    private final Context ctx;
    private final TextView now;
    @androidx.annotation.Nullable
    private final TextView forecast;   // null with weather_forecast off
    private final SceneConfig cfg;
    private final IntConsumer onCode;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean running;
    private boolean inFlight;

    private final Runnable fetch = this::fetchNow;

    WeatherLine(Context ctx, TextView now, TextView forecast, SceneConfig cfg, IntConsumer onCode) {
        this.ctx = ctx;
        this.now = now;
        this.forecast = forecast;
        this.cfg = cfg;
        this.onCode = onCode;
        this.prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    void start() {
        if (!cfg.weatherOn || running) return;
        running = true;
        showSaved();
        long age = System.currentTimeMillis() - prefs.getLong("time", 0L);
        handler.postDelayed(fetch, age >= REFRESH_MS || age < 0 ? 0L : REFRESH_MS - age);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(fetch);
    }

    /** Called every minute with the clock: the forecast's first hour moves on as time passes. */
    void tick() {
        if (running) showSaved();
    }

    private void fetchNow() {
        if (!running || inFlight) return;
        inFlight = true;
        final String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,weather_code"
                        + "&hourly=temperature_2m&forecast_hours=16"
                        + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=4"
                        + "&timezone=auto%s",
                cfg.latitude, cfg.longitude, cfg.fahrenheit ? "&temperature_unit=fahrenheit" : "");
        io.execute(() -> {
            String body = null;
            try {
                body = get(url);
                new JSONObject(body).getJSONObject("current").getDouble("temperature_2m");   // sanity check
            } catch (Exception e) {
                Log.w(TAG, "weather not updated: " + e.getMessage());
                body = null;
            }
            final String json = body;
            handler.post(() -> {
                inFlight = false;
                if (json != null) {
                    prefs.edit().putLong("time", System.currentTimeMillis()).putString("json", json).apply();
                    showSaved();
                }
                if (running) handler.postDelayed(fetch, json == null ? RETRY_MS : REFRESH_MS);
            });
        });
    }

    private void showSaved() {
        long time = prefs.getLong("time", 0L);
        String text = prefs.getString("json", null);
        if (time == 0L || text == null || System.currentTimeMillis() - time > STALE_MS) {
            now.setVisibility(View.INVISIBLE);
            if (forecast != null) forecast.setVisibility(View.INVISIBLE);
            return;
        }
        try {
            JSONObject o = new JSONObject(text);
            JSONObject cur = o.getJSONObject("current");
            int code = cur.optInt("weather_code", -1);
            StringBuilder line = new StringBuilder();
            if (!cfg.weatherCity.isEmpty()) line.append(cfg.weatherCity).append("  ·  ");
            line.append(deg(cur.getDouble("temperature_2m")));
            String words = describe(code);
            if (!words.isEmpty()) line.append(' ').append(words);
            JSONObject daily = o.optJSONObject("daily");
            if (daily != null) {
                JSONArray max = daily.optJSONArray("temperature_2m_max");
                JSONArray min = daily.optJSONArray("temperature_2m_min");
                if (max != null && min != null && max.length() > 0) {
                    line.append("  ·  H ").append(deg(max.getDouble(0)))
                            .append("  L ").append(deg(min.getDouble(0)));
                }
            }
            if (cur.has("apparent_temperature")) {
                line.append("  ·  Feels like ").append(deg(cur.getDouble("apparent_temperature")));
            }
            now.setText(line);
            now.setVisibility(View.VISIBLE);

            if (forecast == null) {
                onCode.accept(code);
                return;
            }
            String hours = hours(o.optJSONObject("hourly"));
            String days = days(daily);
            String both = hours.isEmpty() ? days : days.isEmpty() ? hours : hours + "\n" + days;
            forecast.setText(both.isEmpty() ? " \n " : both);
            forecast.setVisibility(both.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            onCode.accept(code);
        } catch (Exception e) {
            Log.w(TAG, "unreadable weather: " + e.getMessage());
            now.setVisibility(View.INVISIBLE);
            if (forecast != null) forecast.setVisibility(View.INVISIBLE);
        }
    }

    /** Four times, three hours apart, from the next full hour. */
    private String hours(JSONObject hourly) throws Exception {
        if (hourly == null) return "";
        JSONArray times = hourly.optJSONArray("time");
        JSONArray temps = hourly.optJSONArray("temperature_2m");
        if (times == null || temps == null) return "";
        LocalDateTime from = LocalDateTime.now();
        boolean h24 = DateFormat.is24HourFormat(ctx);
        StringBuilder b = new StringBuilder();
        int shown = 0;
        int next = -1;
        for (int i = 0; i < times.length() && shown < 4; i++) {
            LocalDateTime t = LocalDateTime.parse(times.getString(i));
            if (!t.isAfter(from)) continue;
            if (next < 0) next = i;
            if ((i - next) % 3 != 0) continue;
            if (b.length() > 0) b.append("     ");
            int hour = t.getHour();
            String label = h24 ? String.format(Locale.US, "%02d:00", hour)
                    : (hour % 12 == 0 ? 12 : hour % 12) + (hour < 12 ? " AM" : " PM");
            b.append(label).append("  ").append(deg(temps.getDouble(i)));
            shown++;
        }
        return b.toString();
    }

    /** The next three days after today: "Mon 31° / 25°". */
    private String days(JSONObject daily) throws Exception {
        if (daily == null) return "";
        JSONArray dates = daily.optJSONArray("time");
        JSONArray max = daily.optJSONArray("temperature_2m_max");
        JSONArray min = daily.optJSONArray("temperature_2m_min");
        if (dates == null || max == null || min == null) return "";
        LocalDate today = LocalDate.now();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < dates.length(); i++) {
            LocalDate d = LocalDate.parse(dates.getString(i));
            if (!d.isAfter(today)) continue;
            if (b.length() > 0) b.append("     ");
            b.append(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                    .append("  ").append(deg(max.getDouble(i))).append(" / ").append(deg(min.getDouble(i)));
        }
        return b.toString();
    }

    private static String deg(double t) {
        return Math.round(t) + "°";
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather interpretation codes, as Open-Meteo documents them, in plain words. */
    static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "";
        }
    }
}
