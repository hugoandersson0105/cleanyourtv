package com.gathin.tvhome;

/** When a refresh is allowed. The feed is only read while Home is the thing on screen. */
final class CricketPolicy {
    static final long LIVE_MS = 15 * 60 * 1000L;
    static final long IDLE_MS = 60 * 60 * 1000L;
    static final long STALE_LIVE_MS = 20 * 60 * 1000L;
    static final long STALE_IDLE_MS = 70 * 60 * 1000L;
    static final long BACKOFF_START_MS = 10 * 60 * 1000L;
    static final long BACKOFF_MAX_MS = 60 * 60 * 1000L;
    static final int DAILY_CAP = 90;

    private CricketPolicy() {
    }

    static boolean canFetch(boolean resumed, boolean focused, boolean interactive, boolean online) {
        return resumed && focused && interactive && online;
    }

    static long interval(boolean live) {
        return live ? LIVE_MS : IDLE_MS;
    }

    static boolean stale(long now, long fetchedAt, boolean live) {
        if (fetchedAt <= 0 || now < fetchedAt) return false;
        return now - fetchedAt > (live ? STALE_LIVE_MS : STALE_IDLE_MS);
    }

    /** How long to wait before the next request. Zero means it is due. */
    static long waitMs(long now, long fetchedAt, boolean live, long backoffUntil) {
        long due = fetchedAt <= 0 ? now : fetchedAt + interval(live);
        long at = Math.max(due, backoffUntil);
        return Math.max(0, at - now);
    }

    static long nextBackoff(long current) {
        if (current <= 0) return BACKOFF_START_MS;
        long doubled = current > BACKOFF_MAX_MS / 2 ? BACKOFF_MAX_MS : current * 2;
        return Math.min(doubled, BACKOFF_MAX_MS);
    }
}
