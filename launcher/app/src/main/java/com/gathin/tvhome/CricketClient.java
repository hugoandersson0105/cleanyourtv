package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One request to CricketData. The address, which contains the key, is never put in an error. */
final class CricketClient {
    private static final int MAX_BYTES = 256 * 1024;

    private CricketClient() {
    }

    static String fetch(String key) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address(key)).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("cricket feed HTTP " + code);
            }
            return read(connection.getInputStream());
        } catch (IOException e) {
            throw new IOException("cricket feed failed");
        } finally {
            connection.disconnect();
        }
    }

    static String address(String key) {
        StringBuilder out = new StringBuilder("https://api.cricapi.com/v1/cricScore?apikey=");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-') {
                out.append(c);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > MAX_BYTES) throw new IOException("cricket feed was too large");
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
