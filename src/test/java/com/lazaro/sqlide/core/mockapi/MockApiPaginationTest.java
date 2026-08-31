package com.lazaro.sqlide.core.mockapi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockApiPaginationTest {

    @Test
    void returnsFullListWhenQueryIsAbsent() {
        List<String> rows = List.of("a", "b", "c");
        MockApiPagination.Slice slice = MockApiPagination.parse(null, rows.size());
        assertEquals(rows, slice.apply(rows));
        assertEquals(1, slice.page());
        assertEquals(3, slice.total());
    }

    @Test
    void slicesOneBasedPages() {
        List<Integer> rows = List.of(1, 2, 3, 4, 5);
        MockApiPagination.Slice first = MockApiPagination.parse("page=1&limit=2", rows.size());
        assertEquals(List.of(1, 2), first.apply(rows));
        MockApiPagination.Slice second = MockApiPagination.parse("page=2&limit=2", rows.size());
        assertEquals(List.of(3, 4), second.apply(rows));
        MockApiPagination.Slice last = MockApiPagination.parse("page=3&limit=2", rows.size());
        assertEquals(List.of(5), last.apply(rows));
        MockApiPagination.Slice empty = MockApiPagination.parse("page=9&limit=2", rows.size());
        assertTrue(empty.apply(rows).isEmpty());
    }

    @Test
    void limitWithoutPageTakesTheFirstPage() {
        List<Integer> rows = List.of(1, 2, 3, 4);
        assertEquals(List.of(1, 2), MockApiPagination.parse("limit=2", rows.size()).apply(rows));
    }

    @Test
    void rejectsInvalidPage() {
        assertThrows(IllegalArgumentException.class,
                () -> MockApiPagination.parse("page=nope", 10));
    }
}
