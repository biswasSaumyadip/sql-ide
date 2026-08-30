package com.lazaro.sqlide.core.redis;

import java.util.Locale;

/**
 * Read-command templates for a Redis key, chosen from {@code TYPE}.
 */
public final class RedisReadCommands {

    private RedisReadCommands() {
    }

    /**
     * Quotes {@code key} for a Redis CLI line when it contains whitespace or quotes.
     */
    public static String quote(String key) {
        if (key == null) {
            return "\"\"";
        }
        boolean needsQuotes = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'') {
                needsQuotes = true;
                break;
            }
        }
        if (!needsQuotes) {
            return key;
        }
        return "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** {@code GET} / {@code HGETALL} / {@code LRANGE} / {@code SMEMBERS} for {@code type}. */
    public static String forType(String key, String type) {
        String quoted = quote(key);
        String kind = type == null ? "string" : type.trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "hash" -> "HGETALL " + quoted;
            case "list" -> "LRANGE " + quoted + " 0 -1";
            case "set" -> "SMEMBERS " + quoted;
            case "zset" -> "ZRANGE " + quoted + " 0 -1 WITHSCORES";
            case "stream" -> "XRANGE " + quoted + " - +";
            default -> "GET " + quoted;
        };
    }

    /** Prefixes {@code SELECT n} when {@code dbIndex} is not the default database. */
    public static String forType(String key, String type, int dbIndex) {
        String command = forType(key, type);
        if (dbIndex <= 0) {
            return command;
        }
        return "SELECT " + dbIndex + "\n" + command;
    }
}
