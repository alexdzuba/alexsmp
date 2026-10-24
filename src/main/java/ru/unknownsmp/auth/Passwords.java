package ru.unknownsmp.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Хеширование паролей: PBKDF2-HMAC-SHA256 с индивидуальной солью 128 бит. Число итераций по умолчанию — 600 000
 * (рекомендация OWASP Password Storage Cheat Sheet для PBKDF2-HMAC-SHA256), настраивается в auth.yml.
 * При входе хеш с устаревшим числом итераций автоматически пересчитывается.
 * Пароли и хеши никогда не пишутся в журналы.
 */
public final class Passwords {
    private Passwords() {}

    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALG = "PBKDF2WithHmacSHA256";

    public static String hash(String password, int iterations) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] h = derive(password.toCharArray(), salt, iterations);
        return "pbkdf2_sha256$" + iterations + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(h);
    }

    public static boolean verify(String password, String stored) {
        if (stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2_sha256")) return false;
        try {
            int it = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = derive(password.toCharArray(), salt, it);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    public static int iterationsOf(String stored) {
        try {
            return Integer.parseInt(stored.split("\\$")[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] derive(char[] pw, byte[] salt, int it) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw, salt, it, 256);
            byte[] out = SecretKeyFactory.getInstance(ALG).generateSecret(spec).getEncoded();
            spec.clearPassword();
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 недоступен", e);
        }
    }

    public static String randomPassword(int len) {
        String abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(abc.charAt(RNG.nextInt(abc.length())));
        return sb.toString();
    }
}
