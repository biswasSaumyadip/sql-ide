package com.lazaro.sqlide.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic checks for the value types, no database required. */
class RecordsTest {

    @Test
    @DisplayName("ConnectionConfig builds a driver-specific JDBC URL")
    void buildsJdbcUrl() {
        var mysql = ConnectionConfig.mysql("db.internal", 3307, "sales", "root", "secret");
        assertEquals("jdbc:mysql://db.internal:3307/sales", mysql.jdbcUrl());

        var h2 = new ConnectionConfig("localhost", 9092, "scratch", "sa", "",
                ConnectionConfig.Driver.H2_MEMORY);
        assertEquals("jdbc:h2:mem:scratch;DB_CLOSE_DELAY=-1", h2.jdbcUrl());
    }

    @Test
    @DisplayName("ConnectionConfig never leaks the password through toString")
    void masksPassword() {
        var config = ConnectionConfig.mysql("localhost", 3306, "sales", "root", "hunter2");

        assertFalse(config.toString().contains("hunter2"));
        assertTrue(config.toString().contains("****"));
        assertEquals("hunter2", config.password(), "the accessor must still expose the real value");
    }

    @Test
    @DisplayName("ConnectionConfig rejects nonsense endpoints")
    void validatesEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfig.mysql("  ", 3306, "sales", "root", ""));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfig.mysql("localhost", 0, "sales", "root", ""));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionConfig.mysql("localhost", 70_000, "sales", "root", ""));
        assertThrows(NullPointerException.class,
                () -> new ConnectionConfig("localhost", 3306, "sales", "root", "", null));
    }

    @Test
    @DisplayName("QueryResult copies its input so later mutation cannot affect it")
    void copiesInput() {
        List<String> columns = new ArrayList<>(List.of("id"));
        List<List<String>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(Arrays.asList("1")));

        QueryResult result = QueryResult.ofRows(columns, rows, 5L);
        columns.add("injected");
        rows.clear();

        assertEquals(List.of("id"), result.columnNames());
        assertEquals(1, result.rowCount());
    }

    @Test
    @DisplayName("QueryResult tolerates null cells representing SQL NULL")
    void toleratesNullCells() {
        QueryResult result = QueryResult.ofRows(List.of("name"), List.of(Arrays.asList((String) null)), 1L);

        assertNull(result.rows().get(0).get(0));
    }

    @Test
    @DisplayName("QueryResult summarises each outcome distinctly")
    void summarises() {
        assertEquals("1 row in 5 ms", QueryResult.ofRows(List.of("id"), List.of(List.of("1")), 5L).summary());
        assertEquals("3 rows affected in 2 ms", QueryResult.ofUpdate(3, 2L).summary());
        assertTrue(QueryResult.ofError("boom", 1L).summary().contains("boom"));
        assertTrue(QueryResult.ofError(null, 1L).isError());
    }

    @Test
    @DisplayName("ColumnNode renders sizes only where they carry meaning")
    void rendersTypes() {
        assertEquals("VARCHAR(255)", column("VARCHAR", 255, 0).displayType());
        assertEquals("DECIMAL(10,2)", column("DECIMAL", 10, 2).displayType());
        assertEquals("INT", column("INT", 10, 0).displayType());
        assertEquals("TIMESTAMP", column("TIMESTAMP", 26, 6).displayType());
        assertEquals("id : INT  [PK]", new ColumnNode("id", "INT", 10, 0, false, 1, true).label());
    }

    @Test
    @DisplayName("schema nodes defend their collections")
    void nodesAreImmutable() {
        var table = TableNode.of("sales", "orders", "TABLE");
        assertEquals("sales.orders", table.qualifiedName());
        assertThrows(UnsupportedOperationException.class, () -> table.columns().add(column("x", 1, 0)));

        var database = DatabaseNode.of("sales").withTables(List.of(table));
        assertEquals(1, database.tables().size());
        assertThrows(UnsupportedOperationException.class, () -> database.tables().clear());
    }

    private static ColumnNode column(String type, int size, int scale) {
        return new ColumnNode("c", type, size, scale, true, 1, false);
    }
}
