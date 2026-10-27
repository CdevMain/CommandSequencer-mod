package csequencer.core.security;

import java.util.List;
import java.util.Locale;

/**
 * Validates command text before execution. Blocks blacklisted root commands, including when
 * they are nested behind "execute ... run", and rejects multi-line or oversized input.
 */
public final class CommandFilter {
    public static final int MAX_LENGTH = 32000;

    private CommandFilter() {}

    /** Returns null when allowed, or a human readable reason. */
    public static String check(String command, List<String> blacklist) {
        if (command == null || command.isBlank()) return "Empty command";
        if (command.length() > MAX_LENGTH) return "Command too long";
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return "Multi-line commands are not allowed";
        String c = command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        String[] tokens = c.split("\\s+");
        if (tokens.length == 0) return "Empty command";
        String reason = checkRoot(tokens[0], blacklist);
        if (reason != null) return reason;
        // Inspect every "run <cmd>" segment of execute chains.
        for (int i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equalsIgnoreCase("run")) {
                reason = checkRoot(tokens[i + 1], blacklist);
                if (reason != null) return reason;
            }
        }
        return null;
    }

    private static String checkRoot(String token, List<String> blacklist) {
        String root = token.toLowerCase(Locale.ROOT);
        if (root.startsWith("/")) root = root.substring(1);
        int colon = root.indexOf(':');
        String bare = colon >= 0 ? root.substring(colon + 1) : root;
        for (String b : blacklist) {
            String bl = b.toLowerCase(Locale.ROOT).trim();
            if (bl.equals(root) || bl.equals(bare)) return "Command '" + bare + "' is blocked by server policy";
        }
        return null;
    }
}
