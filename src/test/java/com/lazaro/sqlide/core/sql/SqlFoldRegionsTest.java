package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlFoldRegionsTest {

    @Test
    void findsMultiLineParentheses() {
        String sql = """
                SELECT *
                FROM t
                WHERE (a = 1
                  AND b = 2)
                """;
        List<SqlFoldRegions.Region> regions = SqlFoldRegions.find(sql);
        assertFalse(regions.isEmpty());
        SqlFoldRegions.Region region = regions.getFirst();
        assertEquals('(', region.open());
        assertTrue(region.endLine() > region.startLine());
    }

    @Test
    void findsJsonObjectAndArrayFolds() {
        String sql = """
                SELECT '{
                  "a": [
                    1,
                    2
                  ]
                }'
                """;
        List<SqlFoldRegions.Region> regions = SqlFoldRegions.find(sql);
        assertTrue(regions.stream().anyMatch(r -> r.open() == '{'));
        assertTrue(regions.stream().anyMatch(r -> r.open() == '['));
    }

    @Test
    void ignoresBracketsInsideLineComments() {
        String sql = """
                SELECT 1
                -- (not a fold
                FROM t
                """;
        assertTrue(SqlFoldRegions.find(sql).isEmpty());
    }

    @Test
    void byStartLineKeepsEarliestOpenOnALine() {
        String sql = """
                SELECT (
                  1
                )
                """;
        Map<Integer, SqlFoldRegions.Region> map = SqlFoldRegions.byStartLine(sql);
        assertTrue(map.containsKey(0));
        assertEquals('(', map.get(0).open());
    }

    @Test
    void singleLineGroupsAreNotFoldable() {
        assertTrue(SqlFoldRegions.find("SELECT (1) FROM t").isEmpty());
    }
}
