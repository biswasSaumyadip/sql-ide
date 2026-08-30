package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoldManagerTest {

    @Test
    void collapseAndExpandRoundTrip() {
        FoldManager manager = new FoldManager();
        String sql = """
                VALUES (
                  'Thrall',
                  99
                )
                """;
        int open = sql.indexOf('(');
        int close = sql.lastIndexOf(')');
        var prepared = manager.prepareCollapse(sql, open, close);
        assertTrue(prepared.isPresent());
        var replacement = prepared.get();
        String collapsed = sql.substring(0, replacement.start())
                + replacement.text()
                + sql.substring(replacement.end());
        manager.commitCollapse(replacement, sql.substring(open, close + 1));

        assertTrue(manager.hasFolds());
        assertEquals(replacement.text(), collapsed.substring(replacement.start(), replacement.start() + replacement.text().length()));
        assertTrue(collapsed.contains("Thrall") || collapsed.contains("..."));

        var fold = manager.folds().getFirst();
        var expand = manager.prepareExpand(collapsed, fold);
        assertTrue(expand.isPresent());
        String restored = collapsed.substring(0, expand.get().start())
                + expand.get().text()
                + collapsed.substring(expand.get().end());
        manager.commitExpand(fold);
        assertEquals(sql, restored);
        assertFalse(manager.hasFolds());
    }

    @Test
    void expandAllRestoresForExecution() {
        FoldManager manager = new FoldManager();
        String sql = "SELECT (\n  1\n)";
        int open = sql.indexOf('(');
        int close = sql.lastIndexOf(')');
        var replacement = manager.prepareCollapse(sql, open, close).orElseThrow();
        String collapsed = sql.substring(0, replacement.start())
                + replacement.text()
                + sql.substring(replacement.end());
        manager.commitCollapse(replacement, sql.substring(open, close + 1));

        assertEquals(sql, manager.expandAll(collapsed));
    }
}
