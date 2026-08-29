package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @DisplayName("SchemaNode defends its collections against later mutation")
    void schemaNodeIsImmutable() {
        List<SchemaNode> children = new ArrayList<>();
        children.add(SchemaNode.of("id", NodeType.COLUMN));
        Map<String, String> metadata = new HashMap<>();
        metadata.put(SchemaNode.META_CATALOG, "sales");

        var table = new SchemaNode("orders", NodeType.TABLE, children, metadata);
        children.clear();
        metadata.clear();

        assertEquals(1, table.children().size());
        assertEquals("sales", table.metadata(SchemaNode.META_CATALOG));
        assertThrows(UnsupportedOperationException.class, () -> table.children().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> table.metadata().put("x", "y"));
    }

    @Test
    @DisplayName("SchemaNode reports leaves by type, not by whether children were loaded")
    void schemaNodeLeafDependsOnType() {
        var unexpandedTable = SchemaNode.of("orders", NodeType.TABLE);
        assertTrue(unexpandedTable.children().isEmpty());
        assertFalse(unexpandedTable.isLeaf(), "an unexpanded table is not a leaf");

        assertTrue(SchemaNode.of("id", NodeType.COLUMN).isLeaf());
        assertFalse(NodeType.COLUMN.isContainer());
        assertTrue(NodeType.DATABASE.isContainer());
    }

    @Test
    @DisplayName("SchemaNode qualifies its name only when a catalog is known")
    void schemaNodeQualifiesName() {
        assertEquals("sales.orders",
                SchemaNode.of("orders", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "sales")).qualifiedName());
        assertEquals("orders", SchemaNode.of("orders", NodeType.TABLE).qualifiedName());
        assertEquals("orders",
                SchemaNode.of("orders", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "")).qualifiedName());
    }

    @Test
    @DisplayName("the registry hands out drivers by id and rejects unknown ones")
    void registryResolvesDrivers() {
        var registry = DriverRegistry.withDefaults();

        assertTrue(registry.isRegistered(JdbcSqlDriver.ID));
        try (DataSourceDriver driver = registry.create(JdbcSqlDriver.ID)) {
            assertInstanceOf(JdbcSqlDriver.class, driver);
            assertEquals(JdbcSqlDriver.ID, driver.capabilities().id());
            assertFalse(driver.isConnected(), "a freshly created driver must not be connected");
        }

        var failure = assertThrows(IllegalArgumentException.class, () -> registry.create("redis"));
        assertTrue(failure.getMessage().contains("redis"));
    }

    @Test
    @DisplayName("the registry accepts additional drivers at runtime")
    void registryAcceptsNewDrivers() {
        var registry = new DriverRegistry();
        registry.register("jdbc-custom", JdbcSqlDriver::new);

        assertEquals(Set.of("jdbc-custom"), registry.ids());
        try (DataSourceDriver driver = registry.create("jdbc-custom")) {
            assertNotNull(driver);
        }
    }
}
