package com.lazaro.sqlide.core.redis;

import java.util.Locale;
import java.util.Set;

/** Redis commands that add/remove keys or wipe the current DB — refresh the explorer. */
public final class RedisMutatingCommands {

    private static final Set<String> MUTATING = Set.of(
            "FLUSHDB", "FLUSHALL", "DEL", "UNLINK", "RENAME", "RENAMENX");

    private RedisMutatingCommands() {
    }

    public static boolean any(Iterable<String> lines) {
        if (lines == null) {
            return false;
        }
        for (String line : lines) {
            if (mutates(line)) {
                return true;
            }
        }
        return false;
    }

    public static boolean mutates(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String token = firstToken(line.strip());
        return token != null && MUTATING.contains(token.toUpperCase(Locale.ROOT));
    }

    private static String firstToken(String line) {
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        return end == 0 ? null : line.substring(0, end);
    }
}
