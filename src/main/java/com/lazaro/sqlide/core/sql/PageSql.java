package com.lazaro.sqlide.core.sql;

import java.util.Locale;
import java.util.Objects;

/**
 * Rewrites a result-producing statement so the next page can be fetched with
 * {@code LIMIT}/{@code OFFSET} instead of re-reading already-loaded rows.
 */
public final class PageSql {

    public static final String SUBQUERY_ALIAS = "_sqlide_page";

    private PageSql() {
    }

    /**
     * {@code true} when {@code sql} can be wrapped as a derived table. Non-SELECT
     * statements fall back to JDBC skip + {@code setMaxRows}.
     */
    public static boolean canWrap(String sql) {
        String body = stripTrailingSemicolon(sql);
        if (body.isEmpty()) {
            return false;
        }
        String upper = body.toUpperCase(Locale.ROOT);
        return startsWithKeyword(upper, "SELECT") || startsWithKeyword(upper, "WITH");
    }

    /**
     * {@code SELECT * FROM (original) AS _sqlide_page LIMIT pageSize+1 OFFSET offset}.
     * The extra row lets {@code ResultSetMapper} detect that more pages exist.
     */
    public static String wrap(String sql, int offset, int pageSize) {
        String body = stripTrailingSemicolon(sql);
        if (body.isEmpty()) {
            return body;
        }
        int size = Math.max(1, pageSize);
        int off = Math.max(0, offset);
        int fetch = size + 1;
        return "SELECT * FROM (\n" + body + "\n) AS " + SUBQUERY_ALIAS
                + "\nLIMIT " + fetch + " OFFSET " + off;
    }

    public static String stripTrailingSemicolon(String sql) {
        String trimmed = Objects.requireNonNullElse(sql, "").strip();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    private static boolean startsWithKeyword(String upper, String keyword) {
        if (!upper.startsWith(keyword)) {
            return false;
        }
        return upper.length() == keyword.length() || !Character.isLetterOrDigit(upper.charAt(keyword.length()));
    }
}
