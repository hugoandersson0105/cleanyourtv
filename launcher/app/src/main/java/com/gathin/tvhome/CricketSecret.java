package com.gathin.tvhome;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** The CricketData key, sealed with a key that never leaves the TV's keystore. */
final class CricketSecret {
    private static final String ALIAS = "cleanyourtv_cricket";
    private static final String FILE = "cricket.sealed";
    private static final String IMPORT = "cricket.key";
    private static final int GCM_TAG_BITS = 128;

    private CricketSecret() {
    }

    static boolean exists(Context context) {
        return new File(context.getFilesDir(), FILE).isFile();
    }

    static void put(Context context, String key) throws GeneralSecurityException, IOException {
        if (!looksValid(key)) throw new IllegalArgumentException("cricket key was refused");
        byte[] plain = key.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret());
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plain);
            ByteBuffer buffer = ByteBuffer.allocate(4 + iv.length + body.length);
            buffer.putInt(iv.length);
            buffer.put(iv);
            buffer.put(body);
            atomic(new File(context.getFilesDir(), FILE), buffer.array());
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    static String get(Context context) throws GeneralSecurityException, IOException {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.isFile() || file.length() <= 0 || file.length() > 4096) return "";
        byte[] stored = read(file);
        ByteBuffer buffer = ByteBuffer.wrap(stored);
        if (buffer.remaining() < 4) return "";
        int ivLength = buffer.getInt();
        if (ivLength < 12 || ivLength > 32 || buffer.remaining() < ivLength) return "";
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] body = new byte[buffer.remaining()];
        buffer.get(body);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = cipher.doFinal(body);
        try {
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    static void clear(Context context) {
        new File(context.getFilesDir(), FILE).delete();
        File dir = context.getExternalFilesDir(null);
        if (dir != null) new File(dir, IMPORT).delete();
    }

    /**
     * A key left in the app's external files dir (for a one-time setup from another computer).
     * The file is removed either way, and nothing is written when it does not look like a key.
     */
    static boolean importExternal(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return false;
        File file = new File(dir, IMPORT);
        if (!file.isFile()) return false;
        String text = "";
        try {
            if (file.length() <= 0 || file.length() > 200) return false;
            byte[] bytes = read(file);
            text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (!looksValid(text)) return false;
            put(context, text);
            return true;
        } catch (GeneralSecurityException | IOException e) {
            return false;
        } finally {
            file.delete();
            text = "";
        }
    }

    static boolean looksValid(String key) {
        return key != null && key.matches("[0-9a-fA-F-]{16,80}");
    }

    private static SecretKey secret() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    private static void atomic(File dest, byte[] bytes) throws IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(bytes);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IOException("cricket key was not saved");
        }
    }

    private static byte[] read(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        FileInputStream in = new FileInputStream(file);
        try {
            int got = 0;
            while (got < bytes.length) {
                int n = in.read(bytes, got, bytes.length - got);
                if (n < 0) break;
                got += n;
            }
            if (got != bytes.length) throw new IOException("cricket key was unreadable");
            return bytes;
        } finally {
            in.close();
        }
    }
}
