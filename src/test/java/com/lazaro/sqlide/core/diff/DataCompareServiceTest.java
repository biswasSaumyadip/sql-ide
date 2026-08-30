package com.lazaro.sqlide.core.diff;

import com.lazaro.sqlide.core.db.QueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataCompareServiceTest {

    @Test
    void comparesKeyedRows() {
        QueryResult left = QueryResult.ofRows(
                List.of("id", "name"),
                List.of(List.of("1", "Ada"), List.of("2", "Bob")),
                1);
        QueryResult right = QueryResult.ofRows(
                List.of("id", "name"),
                List.of(List.of("1", "Ada"), List.of("2", "Bobby"), List.of("3", "Cid")),
                1);

        DataCompareService.DataDiff diff = DataCompareService.compare(left, right, List.of("id"));
        assertEquals(1, diff.matchCount());
        assertEquals(1, diff.changedCount());
        assertEquals(0, diff.leftOnlyCount());
        assertEquals(1, diff.rightOnlyCount());
    }
}
