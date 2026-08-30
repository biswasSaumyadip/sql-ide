package com.lazaro.sqlide.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlIdentifierAtCaretTest {

    @Test
    void parsesQualifiedNames() {
        var ref = SqlIdentifierAtCaret.parse("warcraft.faction.id").orElseThrow();
        assertEquals("warcraft", ref.catalogOrSchema());
        assertEquals("faction", ref.tableOrColumn());
        assertEquals("id", ref.column());
    }

    @Test
    void resolvesUnderCaret() {
        String sql = "SELECT * FROM warcraft.users WHERE id = 1";
        int caret = sql.indexOf("users") + 2;
        var ref = SqlIdentifierAtCaret.resolve(sql, caret, null).orElseThrow();
        assertEquals("warcraft", ref.catalogOrSchema());
        assertEquals("users", ref.tableOrColumn());
    }

    @Test
    void prefersSelection() {
        var ref = SqlIdentifierAtCaret.resolve("SELECT x FROM t", 0, "schema.table").orElseThrow();
        assertEquals("schema", ref.catalogOrSchema());
        assertEquals("table", ref.tableOrColumn());
        assertTrue(ref.column() == null);
    }
}
