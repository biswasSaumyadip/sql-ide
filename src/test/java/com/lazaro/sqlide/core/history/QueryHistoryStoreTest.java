package com.lazaro.sqlide.core.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryHistoryStoreTest {

    @TempDir
    Path temp;

    @Test
    void persistsAndSearchesAcrossInstances() {
        Path file = temp.resolve("history.json");
        QueryHistoryStore store = new QueryHistoryStore(file);
        store.record("SELECT 1", "1 row in 2 ms", true, 2);
        store.record("DELETE FROM users", "3 rows affected", true, 5);

        QueryHistoryStore reloaded = new QueryHistoryStore(file);
        assertEquals(2, reloaded.entries().size());
        assertEquals("DELETE FROM users", reloaded.entries().getFirst().sql());
        List<QueryHistoryStore.Entry> hits = reloaded.search("select");
        assertEquals(1, hits.size());
        assertTrue(hits.getFirst().sql().toLowerCase().contains("select"));
    }
}
