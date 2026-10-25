package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Which cached matches to put on Home, and the one line each of them gets. */
final class CricketBoard {
    static final int LIMIT = 3;

    private CricketBoard() {
    }

    static List<CricketMatch> choose(List<CricketMatch> matches, List<String> codes, int limit) {
        if (matches == null || matches.isEmpty() || codes == null || codes.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        List<CricketMatch> chosen = new ArrayList<>();
        for (CricketMatch match : matches) {
            if (rank(match, codes) != Integer.MAX_VALUE) chosen.add(match);
        }
        Collections.sort(chosen, Comparator
                .comparing((CricketMatch match) -> !match.live)
                .thenComparingInt(match -> rank(match, codes))
                .thenComparingLong(match -> match.startEpoch == 0L ? Long.MAX_VALUE : match.startEpoch));
        if (chosen.size() > limit) return new ArrayList<>(chosen.subList(0, limit));
        return chosen;
    }

    static boolean anyLive(List<CricketMatch> matches) {
        for (CricketMatch match : matches) {
            if (match.live) return true;
        }
        return false;
    }

    /** Lower is a team nearer the top of the owner's list. No listed team is {@link Integer#MAX_VALUE}. */
    static int rank(CricketMatch match, List<String> codes) {
        int best = Integer.MAX_VALUE;
        int first = codes.indexOf(match.code1);
        int second = codes.indexOf(match.code2);
        if (first >= 0) best = first;
        if (second >= 0 && second < best) best = second;
        return best;
    }

    static String line(CricketMatch match) {
        String teams = side(match.code1, match.name1, match.score1)
                + "   " + side(match.code2, match.name2, match.score2);
        String status = match.status.trim();
        if (status.isEmpty() && match.live) status = "Live";
        if (status.isEmpty()) return teams;
        return teams + "   ·   " + status;
    }

    static String block(List<CricketMatch> matches) {
        StringBuilder out = new StringBuilder();
        for (CricketMatch match : matches) {
            if (out.length() > 0) out.append('\n');
            out.append(line(match));
        }
        return out.toString();
    }

    private static String side(String code, String name, String score) {
        String who = code == null || code.isEmpty() ? name : code;
        String value = score == null ? "" : score.trim();
        if (value.isEmpty()) return who;
        return who + " " + value;
    }
}
