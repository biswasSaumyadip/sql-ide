package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoldSummariesTest {

    @Test
    void summarisesJsonObjectKeys() {
        assertEquals("{ 3 keys }", FoldSummaries.generateFoldSummary("""
                {
                  "a": 1,
                  "b": 2,
                  "c": {"nested": true}
                }
                """));
        assertEquals("{ 1 key }", FoldSummaries.generateFoldSummary("{ \"only\": 1 }"));
        assertEquals("{ 0 keys }", FoldSummaries.generateFoldSummary("{}"));
    }

    @Test
    void summarisesJsonArrayItems() {
        assertEquals("[ 5 items ]", FoldSummaries.generateFoldSummary("[1, 2, 3, 4, 5]"));
        assertEquals("[ 1 item ]", FoldSummaries.generateFoldSummary("[\"solo\"]"));
        assertEquals("[ 2 items ]", FoldSummaries.generateFoldSummary("""
                [
                  {"a": 1},
                  {"b": 2}
                ]
                """));
    }

    @Test
    void summarisesSqlTuplesWithPreview() {
        assertEquals("( 'Thrall'... )", FoldSummaries.generateFoldSummary("""
                (
                  'Thrall',
                  99,
                  true
                )
                """));
        assertEquals("( 1, 2 )", FoldSummaries.generateFoldSummary("( 1, 2 )"));
    }

    @Test
    void ignoresCommasInsideStringsAndNesting() {
        assertEquals(1, FoldSummaries.countTopLevelItems("{\"a\": \"x,y\"}"));
        assertEquals(2, FoldSummaries.countTopLevelItems("{\"a\": [1, 2], \"b\": 3}"));
        assertEquals(2, FoldSummaries.countTopLevelItems("('a,b', 2)"));
    }
}
