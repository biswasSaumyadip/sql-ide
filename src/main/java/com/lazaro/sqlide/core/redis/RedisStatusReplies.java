package com.lazaro.sqlide.core.redis;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Distinguishes Redis write/status replies ({@code OK}, integers) from read
 * results that belong in a grid.
 */
public final class RedisStatusReplies {

    /**
     * Commands whose reply is typically a value or collection the user wants to
     * inspect in the results table — even when that reply is a single cell.
     */
    private static final Set<String> READ_LIKE = Set.of(
            "GET", "GETRANGE", "GETBIT", "GETDEL", "GETEX", "GETSET", "MGET",
            "HGET", "HMGET", "HGETALL", "HKEYS", "HVALS", "HEXISTS", "HLEN", "HSTRLEN",
            "HSCAN", "HRANDFIELD",
            "LRANGE", "LINDEX", "LLEN", "LPOS", "LPOP", "RPOP", "LMOVE", "LMPOP",
            "SMEMBERS", "SCARD", "SISMEMBER", "SRANDMEMBER", "SPOP", "SSCAN",
            "SDIFF", "SINTER", "SUNION",
            "ZRANGE", "ZRANGEBYLEX", "ZRANGEBYSCORE", "ZREVRANGE", "ZREVRANGEBYLEX",
            "ZREVRANGEBYSCORE", "ZSCORE", "ZMSCORE", "ZCARD", "ZCOUNT", "ZLEXCOUNT",
            "ZRANK", "ZREVRANK", "ZSCAN", "ZPOPMAX", "ZPOPMIN", "ZRANDMEMBER",
            "KEYS", "SCAN", "TYPE", "TTL", "PTTL", "EXPIRETIME", "PEXPIRETIME",
            "EXISTS", "TOUCH", "STRLEN", "DUMP", "OBJECT", "MEMORY",
            "INFO", "CONFIG", "CLIENT", "COMMAND", "SLOWLOG", "TIME", "LASTSAVE",
            "DBSIZE", "ROLE", "PUBSUB", "ECHO", "RANDOMKEY",
            "BITCOUNT", "BITPOS", "PFCOUNT",
            "GEODIST", "GEOHASH", "GEOPOS", "GEORADIUS", "GEOSEARCH",
            "XRANGE", "XREVRANGE", "XREAD", "XREADGROUP", "XLEN", "XPENDING", "XINFO",
            "XCLAIM", "XAUTOCLAIM"
    );

    private RedisStatusReplies() {
    }

    /**
     * {@code true} when a scalar {@code Value} cell should be logged as
     * {@code Reply: OK} / {@code Reply: (integer) 1} instead of a result table.
     */
    public static boolean isStatusResult(String command, List<String> columns, int rowCount) {
        if (isReadLike(command)) {
            return false;
        }
        if (columns == null || columns.size() != 1 || rowCount > 1) {
            return false;
        }
        return RedisResultMapper.COL_VALUE.equals(columns.getFirst());
    }

    public static boolean isReadLike(String command) {
        String name = commandName(command);
        return name != null && READ_LIKE.contains(name);
    }

    /**
     * Redis CLI-style status text: {@code OK}, {@code PONG}, {@code (nil)},
     * {@code (integer) 1}.
     */
    public static String formatReply(Object reply, String fallback) {
        if (reply == null) {
            return RedisResultMapper.NIL;
        }
        if (reply instanceof Number number) {
            return "(integer) " + number.longValue();
        }
        if (reply instanceof byte[] bytes) {
            return formatReply(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), fallback);
        }
        String text = fallback == null || fallback.isBlank()
                ? RedisResultMapper.stringify(reply)
                : fallback;
        if (text != null && text.matches("-?\\d+")) {
            return "(integer) " + text;
        }
        return text == null || text.isBlank() ? "OK" : text;
    }

    public static String commandName(String commandOrLine) {
        if (commandOrLine == null || commandOrLine.isBlank()) {
            return null;
        }
        return RedisCommandParser.parse(commandOrLine)
                .map(parsed -> parsed.command().toUpperCase(Locale.ROOT))
                .orElse(null);
    }
}
