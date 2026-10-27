package csequencer.core.model;

import java.security.SecureRandom;

/** Generates short, stable, human-friendly identifiers ("p_3kf9x2ab"). */
public final class Ids {
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghijkmnopqrstuvwxyz".toCharArray();

    private Ids() {}

    public static String next(String prefix) {
        char[] c = new char[10];
        for (int i = 0; i < c.length; i++) c[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        return prefix + "_" + new String(c);
    }

    /** Valid identifiers: 1-64 chars of [A-Za-z0-9_.:-]. Used to validate client supplied ids. */
    public static boolean isValid(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.' || ch == ':';
            if (!ok) return false;
        }
        return true;
    }

    /** Converts a free-form name into a safe file/identifier stem. */
    public static String slug(String name) {
        if (name == null) return "untitled";
        String s = name.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (s.isEmpty()) s = "untitled";
        return s.length() > 48 ? s.substring(0, 48) : s;
    }
}
