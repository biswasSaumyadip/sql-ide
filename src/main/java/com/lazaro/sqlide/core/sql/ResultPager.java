package com.lazaro.sqlide.core.sql;

/**
 * Chooses how to fetch the next result page: wrap with {@code LIMIT/OFFSET} when
 * the statement is a {@code SELECT}/{@code WITH}, otherwise re-run the original
 * SQL and skip already-loaded rows in the mapper.
 */
public final class ResultPager {

    /** Hard cap for a single "Load remaining" click. */
    public static final int REMAINING_CAP = 50_000;

    public record Plan(String sql, int skipRows, int maxRows) {
    }

    private ResultPager() {
    }

    public static Plan plan(String originalSql, int offset, int pageSize) {
        int size = Math.max(1, pageSize);
        int off = Math.max(0, offset);
        if (PageSql.canWrap(originalSql)) {
            return new Plan(PageSql.wrap(originalSql, off, size), 0, size);
        }
        return new Plan(PageSql.stripTrailingSemicolon(originalSql), off, size);
    }
}
