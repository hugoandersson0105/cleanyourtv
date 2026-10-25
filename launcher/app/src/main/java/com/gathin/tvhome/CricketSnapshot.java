package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Last good matches, in a form that cannot contain the API key. A bad file is ignored. */
final class CricketSnapshot {
    private static final String FILE = "cricket-snapshot.txt";
    private static final char SEP = '\u001f';

    final long fetchedAt;
    final List<CricketMatch> matches;

    CricketSnapshot(long fetchedAt, List<CricketMatch> matches) {
        this.fetchedAt = fetchedAt;
        this.matches = matches;
    }

    static CricketSnapshot read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.isFile()) return null;
        try {
            byte[] bytes = readBytes(file, 256 * 1024);
            if (bytes == null) return null;
            return decode(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    void write(Context context) throws IOException {
        File dir = context.getFilesDir();
        File tmp = new File(dir, FILE + ".tmp");
        File dest = new File(dir, FILE);
        byte[] bytes = encode().getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(bytes);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IOException("cricket cache was not saved");
        }
    }

    String encode() {
        StringBuilder out = new StringBuilder();
        out.append("1\n").append(fetchedAt).append('\n').append(matches.size()).append('\n');
        for (CricketMatch match : matches) {
            out.append(clean(match.id)).append(SEP)
                    .append(clean(match.name1)).append(SEP)
                    .append(clean(match.name2)).append(SEP)
                    .append(clean(match.code1)).append(SEP)
                    .append(clean(match.code2)).append(SEP)
                    .append(clean(match.score1)).append(SEP)
                    .append(clean(match.score2)).append(SEP)
                    .append(clean(match.status)).append(SEP)
                    .append(clean(match.type)).append(SEP)
                    .append(match.live ? '1' : '0').append(SEP)
                    .append(match.startEpoch).append('\n');
        }
        return out.toString();
    }

    static CricketSnapshot decode(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length < 3 || !"1".equals(lines[0])) return null;
        long fetchedAt;
        int count;
        try {
            fetchedAt = Long.parseLong(lines[1]);
            count = Integer.parseInt(lines[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (count < 0 || count > 40 || lines.length < 3 + count) return null;
        List<CricketMatch> matches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String[] parts = lines[3 + i].split(String.valueOf(SEP), -1);
            if (parts.length != 11) return null;
            long start;
            try {
                start = Long.parseLong(parts[10]);
            } catch (NumberFormatException e) {
                return null;
            }
            matches.add(new CricketMatch(parts[0], parts[1], parts[2], parts[3], parts[4],
                    parts[5], parts[6], parts[7], parts[8], "1".equals(parts[9]), start));
        }
        return new CricketSnapshot(fetchedAt, matches);
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static byte[] readBytes(File file, int max) throws IOException {
        long length = file.length();
        if (length <= 0 || length > max) return null;
        byte[] bytes = new byte[(int) length];
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            int got = 0;
            while (got < bytes.length) {
                int n = in.read(bytes, got, bytes.length - got);
                if (n < 0) break;
                got += n;
            }
            if (got != bytes.length) return null;
            return bytes;
        } finally {
            in.close();
        }
    }
}
