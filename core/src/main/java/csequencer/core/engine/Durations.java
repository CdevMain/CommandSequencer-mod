package csequencer.core.engine;

import java.util.Locale;

/** Parses and formats tick durations ("40", "2s", "1.5s", "00:04", "1:02.250", "3t"). */
public final class Durations {
    private Durations() {}

    /** Returns ticks, or 0 when unparseable. */
    public static int parseTicks(String s) {
        if (s == null) return 0;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return 0;
        try {
            if (t.contains(":")) {
                String[] parts = t.split(":");
                double secs = 0;
                for (String p : parts) secs = secs * 60 + Double.parseDouble(p);
                return (int) Math.round(secs * 20);
            }
            if (t.endsWith("ms")) return (int) Math.round(Double.parseDouble(t.substring(0, t.length() - 2)) / 50.0);
            if (t.endsWith("s")) return (int) Math.round(Double.parseDouble(t.substring(0, t.length() - 1)) * 20);
            if (t.endsWith("m")) return (int) Math.round(Double.parseDouble(t.substring(0, t.length() - 1)) * 1200);
            if (t.endsWith("t")) return (int) Math.round(Double.parseDouble(t.substring(0, t.length() - 1)));
            return (int) Math.round(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** mm:ss.mmm for ticks. */
    public static String format(int ticks) {
        long ms = ticks * 50L;
        long m = ms / 60000, s = (ms / 1000) % 60, rem = ms % 1000;
        return String.format(Locale.ROOT, "%02d:%02d.%03d", m, s, rem);
    }

    /** mm:ss for countdowns. */
    public static String clock(int ticks) {
        int secs = (int) Math.ceil(Math.max(0, ticks) / 20.0);
        return String.format(Locale.ROOT, "%02d:%02d", secs / 60, secs % 60);
    }
}
