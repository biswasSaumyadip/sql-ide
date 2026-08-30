package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCodeFormatterTest {

    @Test
    void uppercasesKeywordsAndIndents() {
        String formatted = SqlCodeFormatter.format("select id,name from users where id=1");
        assertTrue(formatted.contains("SELECT"));
        assertTrue(formatted.contains("FROM"));
        assertTrue(formatted.contains("WHERE"));
        assertTrue(formatted.contains("\n"));
    }

    @Test
    void blankInputUnchanged() {
        assertEquals("", SqlCodeFormatter.format(""));
        assertEquals("", SqlCodeFormatter.format(null));
        assertEquals("   ", SqlCodeFormatter.format("   "));
    }
}
