package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageSqlTest {

    @Test
    void wrapsSelectWithLimitAndOffset() {
        String sql = PageSql.wrap("SELECT id FROM characters WHERE active = 1;", 1000, 500);
        assertTrue(sql.contains("SELECT * FROM ("));
        assertTrue(sql.contains("SELECT id FROM characters WHERE active = 1"));
        assertTrue(sql.contains("AS _sqlide_page"));
        assertTrue(sql.contains("LIMIT 501 OFFSET 1000"));
        assertFalse(sql.contains(";"));
    }

    @Test
    void canWrapSelectAndCteButNotCall() {
        assertTrue(PageSql.canWrap("select * from t"));
        assertTrue(PageSql.canWrap("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertFalse(PageSql.canWrap("CALL proc()"));
        assertFalse(PageSql.canWrap("INSERT INTO t VALUES (1)"));
        assertFalse(PageSql.canWrap(""));
    }
}
