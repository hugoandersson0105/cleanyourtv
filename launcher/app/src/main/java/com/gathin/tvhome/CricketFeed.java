package com.gathin.tvhome;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** CricketData's current-matches feed. The key is never copied into the result. */
final class CricketFeed {
    private static final Pattern CODE = Pattern.compile("\\[([A-Za-z0-9]{2,4})\\]");
    private static final int KEEP = 40;

    final List<CricketMatch> matches;
    /** -1 when the feed did not say. */
    final int hitsToday;

    private CricketFeed(List<CricketMatch> matches, int hitsToday) {
        this.matches = matches;
        this.hitsToday = hitsToday;
    }

    static CricketFeed parse(String body, long now, ZoneId zone) {
        try {
            JSONObject root = new JSONObject(body);
            if (!"success".equalsIgnoreCase(root.optString("status"))) {
                throw new IllegalStateException("cricket feed was not successful");
            }
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            JSONArray data = root.optJSONArray("data");
            List<CricketMatch> matches = new ArrayList<>();
            if (data != null) {
                for (int i = 0; i < data.length() && matches.size() < KEEP; i++) {
                    CricketMatch match = match(data.optJSONObject(i), today, zone);
                    if (match != null) matches.add(match);
                }
            }
            return new CricketFeed(matches, hits(root));
        } catch (JSONException e) {
            throw new IllegalStateException("cricket feed could not be read");
        }
    }

    /** Drops a failed response's text when it would repeat the request. */
    static String redact(String message) {
        if (message == null) return "";
        return message.replaceAll("(?i)(apikey=)[^&\\s]*", "$1");
    }

    private static int hits(JSONObject root) {
        JSONObject info = root.optJSONObject("info");
        if (info == null || !info.has("hitsToday")) return -1;
        return info.optInt("hitsToday", -1);
    }

    private static CricketMatch match(JSONObject json, LocalDate today, ZoneId zone) {
        if (json == null) return null;
        String raw1 = json.optString("t1", "");
        String raw2 = json.optString("t2", "");
        String name1 = name(raw1);
        String name2 = name(raw2);
        if (name1.isEmpty() || name2.isEmpty()) return null;
        boolean live = "live".equalsIgnoreCase(json.optString("ms"));
        long start = epoch(json.optString("dateTimeGMT", ""));
        boolean onToday = start > 0L && Instant.ofEpochMilli(start).atZone(zone).toLocalDate().equals(today);
        String score1 = json.optString("t1s", "");
        String score2 = json.optString("t2s", "");
        boolean hasScore = !score1.trim().isEmpty() || !score2.trim().isEmpty();
        // A match flagged live with no score and an old start time is a stale row, not a game in progress.
        if (!onToday && !(live && hasScore)) return null;
        String code1 = code(raw1, name1);
        String code2 = code(raw2, name2);
        return new CricketMatch(json.optString("id", ""), name1, name2, code1, code2,
                score1, score2, json.optString("status", ""), json.optString("matchType", ""), live, start);
    }

    private static String name(String raw) {
        int bracket = raw.indexOf('[');
        String value = bracket >= 0 ? raw.substring(0, bracket) : raw;
        return value.trim();
    }

    private static String code(String raw, String teamName) {
        Matcher matcher = CODE.matcher(raw);
        String fromFeed = matcher.find() ? CricketTeams.canonical(matcher.group(1)) : "";
        if (!fromFeed.isEmpty()) return fromFeed;
        return CricketTeams.codeForName(teamName);
    }

    private static long epoch(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0L;
        String value = raw.trim().replace(' ', 'T');
        try {
            if (value.endsWith("Z")) return Instant.parse(value).toEpochMilli();
            if (value.length() == 10) {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
