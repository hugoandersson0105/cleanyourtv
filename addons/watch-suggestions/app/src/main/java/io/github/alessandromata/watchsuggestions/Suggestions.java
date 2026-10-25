package io.github.alessandromata.watchsuggestions;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * What to watch, computed on the TV: Trakt "most watched this week" + TMDB trending, ranked by a
 * weighted rating + recency, optionally without anime and Asian dramas; only titles with a trailer and a synopsis.
 */
final class Suggestions {
    private static final String TMDB = "https://api.themoviedb.org/3";
    private static final double MEAN = 6.8;
    private static final int PER_KIND = 24;
    /** Idiomas de origem de animes e doramas (ja, ko, zh, th). */
    private static final String ASIAN = "ja ko zh cn th";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    static final class Item {
        int id;
        boolean tv;
        String title, overview, year;
        /** Trailers do TMDB: dublados/BR primeiro (localKeys) e de outros idiomas (otherKeys). */
        List<String> localKeys = new ArrayList<>(), otherKeys = new ArrayList<>();
        double rating, score;
    }

    /** A trailer that really plays: its audio URL and the video already downloaded. */
    static final class Playable {
        String key;
        String audioUrl;
        boolean local;
        File video;
    }

    private Suggestions() {}

    /**
     * Finds a trailer that plays: the TMDB ones in the TV's language first, then a dubbed one found by searching
     * YouTube (this also covers a trailer blocked in the viewer's country) and, unless localOnly, the other
     * languages. The preview is muted, so any language will do there.
     */
    static Playable findPlayable(Context c, Item it, boolean localOnly) {
        java.util.Set<String> tried = new java.util.HashSet<>();
        for (String k : it.localKeys) { tried.add(k); Playable p = tryKey(c, k, true); if (p != null) return p; }
        for (String k : searchDubbed(it)) {
            if (!tried.add(k)) continue;
            Playable p = tryKey(c, k, true);
            if (p != null) return p;
        }
        if (!localOnly) {
            for (String k : it.otherKeys) { if (!tried.add(k)) continue; Playable p = tryKey(c, k, false); if (p != null) return p; }
        }
        return null;
    }

    private static Playable tryKey(Context c, String key, boolean local) {
        String[] urls = resolve(key);
        if (urls == null) return null;
        File f = download(c, urls[0], key + ".mp4");
        if (f == null) return null;
        Playable p = new Playable();
        p.key = key;
        p.audioUrl = urls[1];
        p.local = local;
        p.video = f;
        return p;
    }

    private static String norm(String x) {
        return java.text.Normalizer.normalize(x.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("[^a-z0-9 ]", " ");
    }

    /** Search words per language: the query suffix and the title words that mark a dubbed trailer. */
    private static String[] dubbing() {
        switch (Prefs.lang()) {
            case "pt": return new String[]{"trailer dublado", "dublad", "pt br", "ptbr", "portugues"};
            case "es": return new String[]{"tráiler doblado", "doblad", "castellano", "latino", "espanol"};
            case "fr": return new String[]{"bande-annonce VF", " vf", "francais", "version francaise"};
            case "it": return new String[]{"trailer doppiato", "doppiat", "italiano"};
            case "de": return new String[]{"Trailer Deutsch", "deutsch", "german"};
            default: return null;                                   // no dubbed search for this language
        }
    }

    /** Searches "<title> <dubbed trailer>" on YouTube; returns up to 4 ids, official dubbed trailers first. */
    static List<String> searchDubbed(Item it) {
        List<String> out = new ArrayList<>();
        final String[] dub = dubbing();
        if (dub == null) return out;
        try {
            String q = (it.title + " " + it.year + " " + dub[0]).replace("\"", "").replace("\\", "");
            String body = "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20250101.00.00\",\"hl\":\""
                    + Prefs.lang() + "\",\"gl\":\"" + Prefs.region() + "\"}},\"query\":\"" + q + "\"}";
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", UA);
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            } finally {
                c.disconnect();
            }
            JSONArray secs = new JSONObject(sb.toString()).getJSONObject("contents").getJSONObject("twoColumnSearchResultsRenderer")
                    .getJSONObject("primaryContents").getJSONObject("sectionListRenderer").getJSONArray("contents");
            String key = null;                                  // longest word of the title: it must appear in the video title
            for (String w : norm(it.title).split(" ")) if (w.length() >= 4 && (key == null || w.length() > key.length())) key = w;
            List<String[]> scored = new ArrayList<>();
            for (int i = 0; i < secs.length(); i++) {
                JSONObject isr = secs.getJSONObject(i).optJSONObject("itemSectionRenderer");
                JSONArray cs = isr == null ? null : isr.optJSONArray("contents");
                if (cs == null) continue;
                for (int j = 0; j < cs.length(); j++) {
                    JSONObject v = cs.getJSONObject(j).optJSONObject("videoRenderer");
                    if (v == null) continue;
                    StringBuilder tt = new StringBuilder();
                    JSONArray runs = v.getJSONObject("title").getJSONArray("runs");
                    for (int k = 0; k < runs.length(); k++) tt.append(runs.getJSONObject(k).optString("text"));
                    String t = norm(tt.toString());
                    String owner = norm(v.optJSONObject("ownerText") == null ? "" : v.getJSONObject("ownerText").getJSONArray("runs").getJSONObject(0).optString("text"));
                    String len = v.optJSONObject("lengthText") == null ? "" : v.getJSONObject("lengthText").optString("simpleText");
                    String[] mm = len.split(":");
                    int secsLen = mm.length == 2 ? Integer.parseInt(mm[0]) * 60 + Integer.parseInt(mm[1]) : 9999;
                    if (secsLen < 20 || secsLen > 330) continue;
                    if (!(t.contains("trailer") || t.contains("teaser") || t.contains("bande annonce"))) continue;
                    boolean dubbed = false;
                    for (int d = 1; d < dub.length; d++) if (t.contains(dub[d])) dubbed = true;
                    if (!dubbed) continue;
                    if (key != null && !t.contains(key)) continue;
                    int sc = 4 + (t.contains("oficial") || t.contains("official") || t.contains("officiel") || t.contains("ufficiale") ? 2 : 0);
                    for (String g : new String[]{"brasil", "pictures", "disney", "warner", "universal", "netflix", "paris", "imagem", "paramount", "sony"})
                        if (owner.contains(g)) { sc += 2; break; }
                    for (String bad : new String[]{"legendad", "react", "fan ", "edit", "musica", "parodia", "ia "})
                        if (t.contains(bad)) sc -= 6;
                    scored.add(new String[]{String.valueOf(sc), v.getString("videoId")});
                }
            }
            java.util.Collections.sort(scored, (a, b) -> Integer.parseInt(b[0]) - Integer.parseInt(a[0]));
            for (String[] e : scored) { if (Integer.parseInt(e[0]) > 0 && out.size() < 4) out.add(e[1]); }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The last saved list, to show something at once before the network answers. */
    static List<Item> cached(Context c) {
        return readCache(new File(c.getFilesDir(), "suggestions-" + Prefs.langTag() + ".json"));
    }

    /** Fetches every source again (no TTL). Null if the network failed: the caller keeps what it has. */
    static List<Item> refresh(Context c) {
        try {
            List<Item> fresh = fetch(c);
            if (!fresh.isEmpty()) {
                writeCache(new File(c.getFilesDir(), "suggestions-" + Prefs.langTag() + ".json"), fresh);
                return fresh;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------ trailer

    static final String IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";

    /**
     * Resolves a YouTube trailer into direct URLs the way NewPipe/yt-dlp do: {mp4 video up to 480p, m4a audio}.
     * Null if the video does not play (blocked in the country, removed). YouTube's embedded player is not used:
     * it answers error 152 in a plain WebView, so the app plays the streams with the native player.
     */
    static String[] resolve(String videoId) {
        try {
            String body = "{\"context\":{\"client\":{\"clientName\":\"IOS\",\"clientVersion\":\"20.10.4\","
                    + "\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\",\"osVersion\":\"18.3.2.22D82\","
                    + "\"hl\":\"" + Prefs.lang() + "\",\"gl\":\"" + Prefs.region() + "\"}},\"videoId\":\"" + videoId + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", IOS_UA);
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            } finally {
                c.disconnect();
            }
            JSONObject d = new JSONObject(sb.toString());
            if (!"OK".equals(d.getJSONObject("playabilityStatus").optString("status"))) return null;
            JSONArray f = d.getJSONObject("streamingData").getJSONArray("adaptiveFormats");
            String video = null, audio = null;
            int[] prefV = {135, 134, 136, 133};            // h264 mp4: 480p (leve de baixar), 360p, 720p, 240p
            int bestV = prefV.length;
            for (int i = 0; i < f.length(); i++) {
                JSONObject x = f.getJSONObject(i);
                int tag = x.optInt("itag");
                String url = x.optString("url", "");
                if (url.isEmpty()) continue;
                if (tag == 140) audio = url;
                for (int j = 0; j < prefV.length; j++) if (prefV[j] == tag && j < bestV) { bestV = j; video = url; }
            }
            return video == null ? null : new String[]{video, audio};
        } catch (Exception e) {
            return null;
        }
    }

    /** Deletes every downloaded trailer. Called on leaving the app: nothing stays in the TV's storage. */
    static void clearTrailers(Context c) {
        File[] fs = new File(c.getCacheDir(), "trailers").listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /** Downloads to the cache (the TV plays the local file; MediaPlayer cannot open the googlevideo URL itself). */
    static File download(Context c, String url, String name) {
        File dir = new File(c.getCacheDir(), "trailers");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null && old.length > 3) {                 // only the 3 newest stay (about 20 MB at most)
            java.util.Arrays.sort(old, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            for (int i = 0; i < old.length - 3; i++) old[i].delete();
        }
        if (dir.getUsableSpace() < 300L * 1024 * 1024) return null;   // TV almost full: download nothing
        File out = new File(dir, name);
        if (out.exists() && out.length() > 0) return out;
        File tmp = new File(dir, name + ".part");
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(20000);
            h.setRequestProperty("User-Agent", IOS_UA);
            h.setRequestProperty("Range", "bytes=0-");
            try (InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            } finally {
                h.disconnect();
            }
            return tmp.renameTo(out) ? out : null;
        } catch (Exception e) {
            tmp.delete();
            return null;
        }
    }

    // ------------------------------------------------------------------ rede

    private static List<Item> fetch(Context c) throws Exception {
        final String tmdbKey = Prefs.tmdbKey(c);
        final String traktId = Prefs.traktId(c);
        final boolean hideAsian = Prefs.hideAsian(c);
        if (tmdbKey.isEmpty()) return new ArrayList<>();

        // tmdb_id -> position on Trakt (lower = more watched), per kind
        Map<String, Integer> trakt = new HashMap<>();
        LinkedHashMap<String, Boolean> wanted = new LinkedHashMap<>(); // "m:123" / "t:456"
        for (boolean tv : new boolean[]{false, true}) {
            String p = tv ? "t" : "m";
            int rank = 0;
            for (int id : traktIds(tv, traktId)) {
                trakt.put(p + ":" + id, rank++);
                wanted.put(p + ":" + id, tv);
            }
            for (int id : trendingIds(tv, tmdbKey)) wanted.put(p + ":" + id, tv);
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Item>> futures = new ArrayList<>();
        Map<String, Integer> perKind = new HashMap<>();
        for (Map.Entry<String, Boolean> e : wanted.entrySet()) {
            String p = e.getKey().substring(0, 1);
            int n = perKind.containsKey(p) ? perKind.get(p) : 0;
            if (n >= PER_KIND * 2) continue;
            perKind.put(p, n + 1);
            final int id = Integer.parseInt(e.getKey().substring(2));
            final boolean tv = e.getValue();
            final Integer tr = trakt.get(e.getKey());
            futures.add(pool.submit(() -> detail(tv, id, tmdbKey, tr, hideAsian)));
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        List<Item> out = new ArrayList<>();
        for (Future<Item> f : futures) {
            try {
                if (f.isDone()) {
                    Item it = f.get();
                    if (it != null) out.add(it);
                }
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));
        // interleave movies and series so neither kind owns the top
        List<Item> movies = new ArrayList<>(), shows = new ArrayList<>(), mixed = new ArrayList<>();
        for (Item it : out) (it.tv ? shows : movies).add(it);
        for (int i = 0; i < Math.max(movies.size(), shows.size()); i++) {
            if (i < movies.size()) mixed.add(movies.get(i));
            if (i < shows.size()) mixed.add(shows.get(i));
        }
        return mixed.size() > 40 ? new ArrayList<>(mixed.subList(0, 40)) : mixed;
    }

    private static List<Integer> traktIds(boolean tv, String clientId) {
        List<Integer> ids = new ArrayList<>();
        if (clientId.isEmpty()) return ids;
        try {
            Map<String, String> h = new HashMap<>();
            h.put("trakt-api-key", clientId);
            h.put("trakt-api-version", "2");
            JSONArray a = new JSONArray(get("https://api.trakt.tv/" + (tv ? "shows" : "movies") + "/watched/weekly?limit=40", h));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i).optJSONObject(tv ? "show" : "movie");
                if (o == null) continue;
                int id = o.getJSONObject("ids").optInt("tmdb", 0);
                if (id > 0) ids.add(id);
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private static List<Integer> trendingIds(boolean tv, String key) {
        List<Integer> ids = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(get(TMDB + "/trending/" + (tv ? "tv" : "movie") + "/week?language=" + Prefs.langTag() + "&api_key=" + key, null));
            JSONArray a = o.getJSONArray("results");
            for (int i = 0; i < a.length(); i++) ids.add(a.getJSONObject(i).getInt("id"));
        } catch (Exception ignored) {
        }
        return ids;
    }

    /** Details + trailers. Null if it does not qualify (filtered, no synopsis or no trailer). */
    private static Item detail(boolean tv, int id, String key, Integer traktRank, boolean hideAsian) throws Exception {
        JSONObject o = new JSONObject(get(TMDB + "/" + (tv ? "tv" : "movie") + "/" + id
                + "?language=" + Prefs.langTag() + "&append_to_response=videos&include_video_language=" + Prefs.lang() + ",en,null&api_key=" + key, null));
        String lang = o.optString("original_language", "");
        boolean animation = false;
        JSONArray genres = o.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) {
            if (genres.getJSONObject(i).optInt("id") == 16) animation = true;
        }
        if (hideAsian) {                                       // option: no anime and no Asian dramas
            boolean asian = ASIAN.contains(lang) && !lang.isEmpty();
            if (asian && (animation || tv)) return null;
            if (animation && lang.equals("ja")) return null;
        }

        String overview = o.optString("overview", "").trim();
        if (overview.isEmpty()) {                              // not translated into the TV's language: use the English synopsis
            try {
                overview = new JSONObject(get(TMDB + "/" + (tv ? "tv" : "movie") + "/" + id + "?language=en-US&api_key=" + key, null))
                        .optString("overview", "").trim();
            } catch (Exception ignored) {
            }
        }
        if (overview.isEmpty()) return null;
        Item it = new Item();
        pickTrailers(o.optJSONObject("videos"), it);
        if (it.localKeys.isEmpty() && it.otherKeys.isEmpty()) return null;

        it.id = id;
        it.tv = tv;
        it.title = o.optString(tv ? "name" : "title", "");
        it.overview = overview;
        String date = o.optString(tv ? "first_air_date" : "release_date", "");
        it.year = date.length() >= 4 ? date.substring(0, 4) : "";
        it.rating = o.optDouble("vote_average", 0);
        int votes = o.optInt("vote_count", 0);
        if (it.title.isEmpty() || it.rating <= 0) return null;

        double m = tv ? 60 : 250;
        double s = votes / (votes + m) * it.rating + m / (votes + m) * MEAN;
        if (traktRank != null) s += 1.5 * Math.max(0, 1 - traktRank / 40.0); // "mais assistido" de verdade pesa mais
        try {
            int age = Calendar.getInstance().get(Calendar.YEAR) - Integer.parseInt(it.year);
            s += age <= 0 ? 0.8 : age == 1 ? 0.5 : age == 2 ? 0.2 : 0;       // recency
        } catch (NumberFormatException ignored) {
        }
        it.score = s;
        return it;
    }

    /** Splits TMDB's trailers: the TV's language in localKeys (Trailer before Teaser, official first), the rest in otherKeys. */
    private static void pickTrailers(JSONObject videos, Item it) {
        JSONArray r = videos == null ? null : videos.optJSONArray("results");
        List<int[]> pt = new ArrayList<>(), en = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject v = r.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site")) || v.optString("key").isEmpty()) continue;
            String type = v.optString("type");
            if (!type.equals("Trailer") && !type.equals("Teaser")) continue;
            int sc = (type.equals("Trailer") ? 4 : 0) + (v.optBoolean("official") ? 2 : 0) + (Prefs.region().equals(v.optString("iso_3166_1")) ? 1 : 0)
                    + ("en".equals(v.optString("iso_639_1")) ? 1 : 0);
            ids.add(v.optString("key"));
            ((Prefs.lang().equals(v.optString("iso_639_1"))) ? pt : en).add(new int[]{sc, ids.size() - 1});
        }
        for (List<int[]> l : java.util.Arrays.asList(pt, en)) {
            java.util.Collections.sort(l, (a, b) -> b[0] - a[0]);
            List<String> dst = l == pt ? it.localKeys : it.otherKeys;
            for (int[] e : l) if (dst.size() < 3) dst.add(ids.get(e[1]));
        }
    }

    private static String get(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ----------------------------------------------------------------- cache

    private static void writeCache(File f, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id).put("tv", it.tv).put("title", it.title).put("overview", it.overview)
                        .put("year", it.year).put("pt", new JSONArray(it.localKeys)).put("en", new JSONArray(it.otherKeys)).put("rating", it.rating).put("score", it.score);
                a.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static List<Item> readCache(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray a = new JSONArray(new String(b, 0, n, StandardCharsets.UTF_8));
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getInt("id");
                it.tv = o.getBoolean("tv");
                it.title = o.getString("title");
                it.overview = o.getString("overview");
                it.year = o.getString("year");
                JSONArray pa = o.getJSONArray("pt"), ea = o.getJSONArray("en");
                for (int k = 0; k < pa.length(); k++) it.localKeys.add(pa.getString(k));
                for (int k = 0; k < ea.length(); k++) it.otherKeys.add(ea.getString(k));
                it.rating = o.getDouble("rating");
                it.score = o.getDouble("score");
                out.add(it);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
