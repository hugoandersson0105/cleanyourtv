package com.gathin.tvhome;

/** One match from the score feed. Codes are already canonical. */
final class CricketMatch {
    final String id;
    final String name1;
    final String name2;
    final String code1;
    final String code2;
    final String score1;
    final String score2;
    final String status;
    final String type;
    final boolean live;
    final long startEpoch;

    CricketMatch(String id, String name1, String name2, String code1, String code2,
            String score1, String score2, String status, String type, boolean live, long startEpoch) {
        this.id = id == null ? "" : id;
        this.name1 = name1 == null ? "" : name1;
        this.name2 = name2 == null ? "" : name2;
        this.code1 = code1 == null ? "" : code1;
        this.code2 = code2 == null ? "" : code2;
        this.score1 = score1 == null ? "" : score1;
        this.score2 = score2 == null ? "" : score2;
        this.status = status == null ? "" : status;
        this.type = type == null ? "" : type;
        this.live = live;
        this.startEpoch = startEpoch;
    }
}
