package com.lazaro.sqlide.core.sql;

import com.github.vertical_blank.sqlformatter.SqlFormatter;
import com.github.vertical_blank.sqlformatter.core.FormatConfig;

/**
 * Pretty-prints SQL via the vertical-blank {@code sql-formatter} library.
 */
public final class SqlCodeFormatter {

    private static final FormatConfig CONFIG = FormatConfig.builder()
            .indent("    ")
            .uppercase(true)
            .linesBetweenQueries(2)
            .build();

    private SqlCodeFormatter() {
    }

    /**
     * Formats {@code sql} with upper-cased keywords. Blank / null input is returned unchanged.
     * On formatter failure the original text is returned so the editor never loses content.
     */
    public static String format(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        try {
            return SqlFormatter.format(sql, CONFIG);
        } catch (RuntimeException ex) {
            return sql;
        }
    }
}
