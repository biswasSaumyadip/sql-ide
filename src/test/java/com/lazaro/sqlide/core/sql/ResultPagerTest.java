package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultPagerTest {

    @Test
    void selectUsesOffsetWrapAndZeroSkip() {
        ResultPager.Plan plan = ResultPager.plan("SELECT * FROM numbers ORDER BY n", 1000, 500);
        assertEquals(0, plan.skipRows());
        assertEquals(500, plan.maxRows());
        assertTrue(plan.sql().contains("LIMIT 501 OFFSET 1000"));
    }

    @Test
    void nonSelectFallsBackToClientSkip() {
        ResultPager.Plan plan = ResultPager.plan("CALL dump_rows()", 200, 100);
        assertEquals("CALL dump_rows()", plan.sql());
        assertEquals(200, plan.skipRows());
        assertEquals(100, plan.maxRows());
    }
}
