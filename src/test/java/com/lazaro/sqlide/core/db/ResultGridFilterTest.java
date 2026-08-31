package com.lazaro.sqlide.core.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultGridFilterTest {

    private static final List<String> ROW = Arrays.asList("Ada", "42", null);

    @Test
    void blankQueriesMatchEverything() {
        assertTrue(ResultGridFilter.matches(ROW, "", List.of()));
        assertTrue(ResultGridFilter.matches(ROW, "  ", Arrays.asList("", null)));
    }

    @Test
    void globalNeedleSearchesAnyCell() {
        assertTrue(ResultGridFilter.matches(ROW, "ada", List.of()));
        assertTrue(ResultGridFilter.matches(ROW, "42", List.of()));
        assertFalse(ResultGridFilter.matches(ROW, "zzz", List.of()));
    }

    @Test
    void columnNeedlesAreAndedAndIgnoreNullCells() {
        assertTrue(ResultGridFilter.matches(ROW, "", List.of("ad", "4")));
        assertFalse(ResultGridFilter.matches(ROW, "", List.of("ad", "99")));
        assertFalse(ResultGridFilter.matches(ROW, "", List.of("", "", "x")),
                "NULL cells do not match a typed column filter");
    }

    @Test
    void globalAndColumnFiltersCombine() {
        assertTrue(ResultGridFilter.matches(ROW, "ada", List.of("", "4")));
        assertFalse(ResultGridFilter.matches(ROW, "ada", List.of("", "99")));
        assertFalse(ResultGridFilter.matches(ROW, "zzz", List.of("ad")));
    }
}
