package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-member sides, in the order they joined Test cricket, plus any scoreboard code the owner adds.
 * The list order is the order matches are preferred in when several are on today.
 */
final class CricketTeams {
    static final class Side {
        final String code;
        final String name;

        Side(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    enum Add {
        OK, BAD, DUPLICATE, FULL
    }

    static final int MAX = 24;

    private static final Side[] FULL = {
            new Side("AUS", "Australia"),
            new Side("ENG", "England"),
            new Side("SA", "South Africa"),
            new Side("WI", "West Indies"),
            new Side("NZ", "New Zealand"),
            new Side("IND", "India"),
            new Side("PAK", "Pakistan"),
            new Side("SL", "Sri Lanka"),
            new Side("ZIM", "Zimbabwe"),
            new Side("BAN", "Bangladesh"),
            new Side("IRE", "Ireland"),
            new Side("AFG", "Afghanistan"),
    };

    /** Other codes the feed uses for the same side. */
    private static final Map<String, String> ALIASES;

    static {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("RSA", "SA");
        aliases.put("SAF", "SA");
        aliases.put("SRI", "SL");
        aliases.put("WIN", "WI");
        aliases.put("NZL", "NZ");
        aliases.put("BD", "BAN");
        aliases.put("ZWE", "ZIM");
        ALIASES = Collections.unmodifiableMap(aliases);
    }

    private CricketTeams() {
    }

    static List<String> defaults() {
        List<String> codes = new ArrayList<>();
        for (Side side : FULL) codes.add(side.code);
        return codes;
    }

    /** A saved list. {@code null} means the owner has never chosen, so the full members apply. */
    static List<String> load(String stored) {
        if (stored == null) return defaults();
        return parse(stored);
    }

    static List<String> parse(String stored) {
        List<String> codes = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return codes;
        for (String piece : stored.split(",")) {
            String code = canonical(piece);
            if (!code.isEmpty() && !codes.contains(code)) codes.add(code);
            if (codes.size() == MAX) break;
        }
        return codes;
    }

    static String join(List<String> codes) {
        StringBuilder out = new StringBuilder();
        for (String code : codes) {
            if (out.length() > 0) out.append(',');
            out.append(code);
        }
        return out.toString();
    }

    static Add add(List<String> codes, String raw) {
        String code = canonical(raw);
        if (code.isEmpty()) return Add.BAD;
        if (codes.contains(code)) return Add.DUPLICATE;
        if (codes.size() >= MAX) return Add.FULL;
        codes.add(code);
        return Add.OK;
    }

    static void move(List<String> codes, int index, int delta) {
        int next = index + delta;
        if (index < 0 || next < 0 || index >= codes.size() || next >= codes.size()) return;
        Collections.swap(codes, index, next);
    }

    static void remove(List<String> codes, int index) {
        if (index < 0 || index >= codes.size()) return;
        codes.remove(index);
    }

    /** Scoreboard code, an alias of one, or any other 2–4 letter code. Empty when it is not a code. */
    static String canonical(String raw) {
        if (raw == null) return "";
        String code = raw.trim().toUpperCase(Locale.US);
        String alias = ALIASES.get(code);
        if (alias != null) return alias;
        if (code.matches("[A-Z]{2,4}")) return code;
        return "";
    }

    static String codeForName(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        for (Side side : FULL) {
            if (side.name.equalsIgnoreCase(trimmed)) return side.code;
        }
        return "";
    }

    /** "India", or empty for a code the owner typed. */
    static String label(String code) {
        for (Side side : FULL) {
            if (side.code.equals(code)) return side.name;
        }
        return "";
    }

    static boolean known(String code) {
        for (Side side : FULL) {
            if (side.code.equals(code)) return true;
        }
        return false;
    }

    static List<Side> fullMembers() {
        return Arrays.asList(FULL);
    }
}
