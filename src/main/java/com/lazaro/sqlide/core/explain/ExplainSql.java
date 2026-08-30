package com.lazaro.sqlide.core.explain;

import com.lazaro.sqlide.core.db.ConnectionConfig;

import java.util.Locale;
import java.util.Objects;

/**
 * Dialect-aware wrapping of a user statement in {@code EXPLAIN} /
 * {@code EXPLAIN ANALYZE} (or the closest vendor equivalent).
 */
public final class ExplainSql {

    private ExplainSql() {
    }

    /**
     * @param sql     statement to explain (must not already be an EXPLAIN)
     * @param driver  connected dialect
     * @param analyze {@code true} for ANALYZE / FORMAT=TREE where supported
     */
    public static String wrap(String sql, ConnectionConfig.Driver driver, boolean analyze) {
        Objects.requireNonNull(driver, "driver must not be null");
        String body = stripTrailingSemicolon(Objects.requireNonNullElse(sql, "").strip());
        if (body.isEmpty()) {
            return body;
        }
        if (looksLikeExplain(body)) {
            return body;
        }
        return switch (driver) {
            case POSTGRESQL -> analyze
                    ? "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + body
                    : "EXPLAIN (FORMAT TEXT) " + body;
            case MYSQL, MARIADB -> analyze
                    ? "EXPLAIN ANALYZE " + body
                    : "EXPLAIN " + body;
            case H2_MEMORY ->
                // H2 has EXPLAIN but not ANALYZE; both modes use EXPLAIN.
                    "EXPLAIN " + body;
        };
    }

    public static boolean looksLikeExplain(String sql) {
        String trimmed = sql.strip();
        return trimmed.regionMatches(true, 0, "EXPLAIN", 0, "EXPLAIN".length());
    }

    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql.strip();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    /** Short label for toolbar / outcome header. */
    public static String modeLabel(boolean analyze, ConnectionConfig.Driver driver) {
        if (driver == ConnectionConfig.Driver.H2_MEMORY) {
            return "EXPLAIN";
        }
        return analyze ? "EXPLAIN ANALYZE" : "EXPLAIN";
    }

    static String driverKey(ConnectionConfig.Driver driver) {
        return driver.name().toLowerCase(Locale.ROOT);
    }
}
