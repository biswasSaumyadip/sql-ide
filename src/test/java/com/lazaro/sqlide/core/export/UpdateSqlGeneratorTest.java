package com.lazaro.sqlide.core.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpdateSqlGeneratorTest {

    @Test
    void buildsUpdateForChangedNonKeyColumns() {
        String sql = UpdateSqlGenerator.update(
                "demo.users",
                List.of("id", "name", "age"),
                List.of("id"),
                List.of("1", "Ann", "30"),
                List.of("1", "Bob", "30"));
        assertEquals("UPDATE demo.users SET `name` = 'Bob' WHERE `id` = '1';", sql);
    }

    @Test
    void returnsNullWhenUnchanged() {
        assertNull(UpdateSqlGenerator.update(
                "demo.users",
                List.of("id", "name"),
                List.of("id"),
                List.of("1", "Ann"),
                List.of("1", "Ann")));
    }

    @Test
    void escapesQuotesAndNulls() {
        String sql = UpdateSqlGenerator.update(
                "t",
                List.of("id", "note"),
                List.of("id"),
                java.util.Arrays.asList("1", null),
                List.of("1", "O'Brien"));
        assertEquals("UPDATE t SET `note` = 'O''Brien' WHERE `id` = '1';", sql);
    }
}
