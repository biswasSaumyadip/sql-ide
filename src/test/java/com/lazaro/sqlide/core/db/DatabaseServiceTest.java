package com.lazaro.sqlide.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_SECONDS;
import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_UNIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseServiceTest {

    private DatabaseService service;

    @BeforeEach
    void connect() throws Exception {
        service = new DatabaseService();
        service.connectAsync(H2TestSupport.freshDatabase()).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
    }

    @AfterEach
    void disconnect() {
        service.close();
    }

    @Test
    @DisplayName("connecting opens a usable pool and remembers the configuration")
    void connectOpensPool() {
        assertTrue(service.isConnected());
        assertTrue(service.currentConfig().isPresent());
        assertEquals(ConnectionConfig.Driver.H2_MEMORY, service.currentConfig().orElseThrow().driver());
    }

    @Test
    @DisplayName("DDL reports no result set")
    void ddlReportsNoResultSet() throws Exception {
        QueryResult result = run("CREATE TABLE widget (id INT PRIMARY KEY, name VARCHAR(50))");

        assertFalse(result.isError(), result.errorMessage());
        assertFalse(result.isResultSet());
        assertTrue(result.columnNames().isEmpty());
    }

    @Test
    @DisplayName("DML reports the number of affected rows")
    void dmlReportsUpdateCount() throws Exception {
        run("CREATE TABLE widget (id INT PRIMARY KEY, name VARCHAR(50))");

        QueryResult result = run("INSERT INTO widget VALUES (1, 'a'), (2, 'b'), (3, 'c')");

        assertFalse(result.isError(), result.errorMessage());
        assertFalse(result.isResultSet());
        assertEquals(3, result.rowCount());
    }

    @Test
    @DisplayName("SELECT materialises columns and rows, preserving SQL NULL as null")
    void selectMaterialisesRows() throws Exception {
        run("CREATE TABLE widget (id INT PRIMARY KEY, name VARCHAR(50))");
        run("INSERT INTO widget VALUES (1, 'alpha'), (2, NULL)");

        QueryResult result = run("SELECT id, name FROM widget ORDER BY id");

        assertFalse(result.isError(), result.errorMessage());
        assertTrue(result.isResultSet());
        assertEquals(List.of("ID", "NAME"), result.columnNames());
        assertEquals(2, result.rowCount());
        assertEquals(List.of("1", "alpha"), result.rows().get(0));
        assertEquals("2", result.rows().get(1).get(0));
        assertNull(result.rows().get(1).get(1), "SQL NULL must stay null, not become an empty string");
    }

    @Test
    @DisplayName("result rows are detached and immutable")
    void rowsAreImmutable() throws Exception {
        run("CREATE TABLE widget (id INT PRIMARY KEY)");
        run("INSERT INTO widget VALUES (1)");

        QueryResult result = run("SELECT id FROM widget");

        assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.rows().get(0).set(0, "x"));
        assertThrows(UnsupportedOperationException.class, () -> result.columnNames().add("x"));
    }

    @Test
    @DisplayName("large result sets are capped at MAX_ROWS")
    void largeResultSetIsCapped() throws Exception {
        QueryResult result = run("SELECT X FROM SYSTEM_RANGE(1, " + (DatabaseService.MAX_ROWS + 500) + ")");

        assertFalse(result.isError(), result.errorMessage());
        assertEquals(DatabaseService.MAX_ROWS, result.rowCount());
    }

    @Test
    @DisplayName("invalid SQL resolves to a failed result rather than a failed future")
    void invalidSqlReturnsErrorResult() throws Exception {
        QueryResult result = run("SELECT * FROM table_that_does_not_exist");

        assertTrue(result.isError());
        assertNotNull(result.errorMessage());
        assertFalse(result.errorMessage().isBlank());
        assertTrue(result.summary().startsWith("Failed"));
    }

    @Test
    @DisplayName("blank input is rejected without touching the pool")
    void blankSqlReturnsErrorResult() throws Exception {
        assertTrue(run("   \n  ").isError());
        assertTrue(run(null).isError());
    }

    @Test
    @DisplayName("executing while disconnected reports an error result")
    void executingWhileDisconnectedReturnsErrorResult() throws Exception {
        service.disconnect();
        assertFalse(service.isConnected());

        QueryResult result = run("SELECT 1");

        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("Not connected"));
    }

    @Test
    @DisplayName("connecting with bad credentials fails the future")
    void badConnectionFailsFuture() {
        try (DatabaseService broken = new DatabaseService()) {
            var config = new ConnectionConfig("localhost", 3306, "nope", "nobody", "wrong",
                    ConnectionConfig.Driver.MYSQL);

            assertThrows(ExecutionException.class,
                    () -> broken.connectAsync(config).get(TIMEOUT_SECONDS, TIMEOUT_UNIT));
        }
    }

    private QueryResult run(String sql) throws Exception {
        return service.executeQueryAsync(sql).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
    }
}
