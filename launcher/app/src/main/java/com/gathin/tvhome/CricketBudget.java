package com.gathin.tvhome;

/** The day's request count, kept as "yyyy-MM-dd count". A new day starts again at zero. */
final class CricketBudget {
    private CricketBudget() {
    }

    static int count(String stored, String day) {
        if (stored == null || day == null) return 0;
        String[] parts = stored.trim().split(" ");
        if (parts.length != 2 || !parts[0].equals(day)) return 0;
        try {
            int count = Integer.parseInt(parts[1]);
            return Math.max(0, count);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean allow(String stored, String day) {
        return count(stored, day) < CricketPolicy.DAILY_CAP;
    }

    static String hit(String stored, String day) {
        return day + " " + (count(stored, day) + 1);
    }

    /** The server's own tally wins when it is ahead of ours. */
    static String observe(String stored, String day, int serverHits) {
        int local = count(stored, day);
        int count = serverHits < 0 ? local : Math.max(local, serverHits);
        return day + " " + count;
    }
}
