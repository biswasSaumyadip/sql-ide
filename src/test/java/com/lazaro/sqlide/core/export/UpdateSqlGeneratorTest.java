package com.lazaro.sqlide.core.export;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
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
                Arrays.asList("1", null),
                List.of("1", "O'Brien"));
        assertEquals("UPDATE t SET `note` = 'O''Brien' WHERE `id` = '1';", sql);
    }

    @Test
    void buildsInsert() {
        String sql = UpdateSqlGenerator.insert(
                "demo.users",
                List.of("id", "name"),
                Arrays.asList("2", null));
        assertEquals("INSERT INTO demo.users (`id`, `name`) VALUES ('2', NULL);", sql);
    }

    @Test
    void buildsDelete() {
        String sql = UpdateSqlGenerator.delete(
                "demo.users",
                List.of("id", "name"),
                List.of("id"),
                List.of("1", "Ann"));
        assertEquals("DELETE FROM demo.users WHERE `id` = '1';", sql);
    }
}
