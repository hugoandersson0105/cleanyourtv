package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class CricketTest {
    private static final ZoneId CHENNAI = ZoneId.of("Asia/Kolkata");
    private static final long NOW = java.time.Instant.parse("2026-10-25T03:30:00Z").toEpochMilli();

    private static final String BODY = "{"
            + "\"apikey\":\"secret-key-value\","
            + "\"status\":\"success\","
            + "\"info\":{\"hitsToday\":4},"
            + "\"data\":["
            + "{\"id\":\"live\",\"t1\":\"India [IND]\",\"t2\":\"Australia [AUS]\",\"t1s\":\"187/4\",\"t2s\":\"\","
            + "\"status\":\"India need 40\",\"ms\":\"live\",\"matchType\":\"t20\","
            + "\"dateTimeGMT\":\"2026-10-20T08:00:00\"},"
            + "{\"id\":\"stale\",\"t1\":\"England [ENG]\",\"t2\":\"Pakistan [PAK]\",\"t1s\":\"\",\"t2s\":\"\","
            + "\"status\":\"Match starts at Oct 20\",\"ms\":\"live\",\"matchType\":\"test\","
            + "\"dateTimeGMT\":\"2026-10-20T08:00:00\"},"
            + "{\"id\":\"today\",\"t1\":\"New Zealand [NZ]\",\"t2\":\"South Africa [RSA]\",\"t1s\":\"\",\"t2s\":\"\","
            + "\"status\":\"Starts soon\",\"ms\":\"fixture\",\"matchType\":\"odi\","
            + "\"dateTimeGMT\":\"2026-10-25T14:00:00\"},"
            + "{\"id\":\"other\",\"t1\":\"Netherlands [NED]\",\"t2\":\"Namibia [NAM]\",\"t1s\":\"\",\"t2s\":\"\","
            + "\"status\":\"Today\",\"ms\":\"fixture\",\"matchType\":\"odi\","
            + "\"dateTimeGMT\":\"2026-10-25T12:00:00\"}"
            + "]}";

    @Test
    public void fullMembersFollowTheOrderTheyWereGiven() {
        List<String> codes = CricketTeams.defaults();
        assertEquals(Arrays.asList("AUS", "ENG", "SA", "WI", "NZ", "IND", "PAK", "SL", "ZIM", "BAN", "IRE", "AFG"),
                codes);
        assertEquals("India", CricketTeams.label("IND"));
        assertEquals("Afghanistan", CricketTeams.label("AFG"));
        assertEquals("", CricketTeams.label("NED"));
    }

    @Test
    public void ownerCanReorderAddAndRemoveByCode() {
        List<String> codes = CricketTeams.defaults();
        CricketTeams.move(codes, 5, -1);
        assertEquals("IND", codes.get(4));
        assertEquals("NZ", codes.get(5));
        assertEquals(CricketTeams.Add.OK, CricketTeams.add(codes, "ned"));
        assertEquals("NED", codes.get(codes.size() - 1));
        assertEquals(CricketTeams.Add.DUPLICATE, CricketTeams.add(codes, "RSA"));
        assertEquals(CricketTeams.Add.BAD, CricketTeams.add(codes, "1"));
        assertFalse(codes.contains("RSA"));
        assertTrue(codes.contains("SA"));
        CricketTeams.remove(codes, codes.size() - 1);
        assertFalse(codes.contains("NED"));
        assertTrue(CricketTeams.load("").isEmpty());
        assertEquals(12, CricketTeams.load(null).size());
    }

    @Test
    public void feedKeepsTodayAndLiveScoresAndDropsTheKey() {
        CricketFeed feed = CricketFeed.parse(BODY, NOW, CHENNAI);
        assertEquals(4, feed.hitsToday);
        assertEquals(3, feed.matches.size());
        assertEquals("IND", feed.matches.get(0).code1);
        assertEquals("SA", feed.matches.get(1).code2);
        assertEquals("NED", feed.matches.get(2).code1);
        CricketSnapshot snap = new CricketSnapshot(NOW, feed.matches);
        assertFalse(snap.encode().contains("secret-key-value"));
        assertFalse(CricketFeed.redact("failed apikey=secret-key-value&x=1").contains("secret"));
        CricketSnapshot back = CricketSnapshot.decode(snap.encode());
        assertEquals(3, back.matches.size());
        assertEquals("Australia", back.matches.get(0).name2);
        assertNull(CricketSnapshot.decode("0\n1\n0\n"));
    }

    @Test
    public void listedTeamsComeFirstAndLiveOutranksALaterSide() {
        CricketFeed feed = CricketFeed.parse(BODY, NOW, CHENNAI);
        List<String> codes = Arrays.asList("NZ", "IND");
        List<CricketMatch> chosen = CricketBoard.choose(feed.matches, codes, 3);
        assertEquals(2, chosen.size());
        assertEquals("live", chosen.get(0).id);
        assertEquals("today", chosen.get(1).id);
        assertTrue(CricketBoard.line(chosen.get(0)).startsWith("IND 187/4   AUS"));
        assertTrue(CricketBoard.choose(feed.matches, Collections.singletonList("PAK"), 3).isEmpty());
        assertEquals("NED   NAM   ·   Today", CricketBoard.line(feed.matches.get(2)));
    }

    @Test
    public void refreshWaitsAndBacksOffAndStopsAtTheDailyCap() {
        assertFalse(CricketPolicy.canFetch(true, true, true, false));
        assertTrue(CricketPolicy.canFetch(true, true, true, true));
        assertEquals(0, CricketPolicy.waitMs(1_000, 0, false, 0));
        assertEquals(CricketPolicy.IDLE_MS - 500, CricketPolicy.waitMs(1_500, 1_000, false, 0));
        assertTrue(CricketPolicy.stale(CricketPolicy.STALE_IDLE_MS + 5, 0, false) == false);
        assertTrue(CricketPolicy.stale(CricketPolicy.STALE_LIVE_MS + 5, 1, true));
        assertEquals(CricketPolicy.BACKOFF_START_MS, CricketPolicy.nextBackoff(0));
        assertEquals(CricketPolicy.BACKOFF_MAX_MS, CricketPolicy.nextBackoff(CricketPolicy.BACKOFF_MAX_MS));
        assertTrue(CricketBudget.allow("2026-10-25 89", "2026-10-25"));
        assertFalse(CricketBudget.allow(CricketBudget.hit("2026-10-25 89", "2026-10-25"), "2026-10-25"));
        assertEquals(7, CricketBudget.count(CricketBudget.observe("2026-10-25 4", "2026-10-25", 7), "2026-10-25"));
        assertEquals(0, CricketBudget.count("2026-10-25 4", "2026-10-26"));
    }
}
