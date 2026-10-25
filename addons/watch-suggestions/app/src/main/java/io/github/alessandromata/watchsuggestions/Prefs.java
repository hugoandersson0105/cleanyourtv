package io.github.alessandromata.watchsuggestions;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** The owner's own API keys and choices, kept in the app's private storage. Nothing is bundled in the APK. */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String tmdbKey(Context c) { return sp(c).getString("tmdb_key", "").trim(); }
    static String traktId(Context c) { return sp(c).getString("trakt_id", "").trim(); }
    static boolean hideAsian(Context c) { return sp(c).getBoolean("hide_asian", false); }

    static void save(Context c, String tmdbKey, String traktId, boolean hideAsian) {
        sp(c).edit().putString("tmdb_key", tmdbKey.trim()).putString("trakt_id", traktId.trim())
                .putBoolean("hide_asian", hideAsian).apply();
    }

    /** The TV's own language: titles, synopses and trailers follow it. TMDB takes "pt-BR", "es-MX", "de". */
    static String langTag() {
        Locale l = Locale.getDefault();
        return l.getCountry().isEmpty() ? l.getLanguage() : l.getLanguage() + "-" + l.getCountry();
    }

    static String lang() { return Locale.getDefault().getLanguage(); }

    static String region() {
        String r = Locale.getDefault().getCountry();
        return r.isEmpty() ? "US" : r;
    }
}
