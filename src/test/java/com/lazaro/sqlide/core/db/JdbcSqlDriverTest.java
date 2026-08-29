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

/**
 * Exercises the driver strictly through {@link DataSourceDriver}, so the tests fail
 * if behaviour is only reachable via the concrete class.
 */
class JdbcSqlDriverTest {

    private DataSourceDriver driver;

    @BeforeEach
    void connect() throws Exception {
        driver = new JdbcSqlDriver();
        driver.connect(H2TestSupport.freshDatabase()).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
    }

    @AfterEach
    void disconnect() {
        driver.close();
    }

    @Test
    @DisplayName("capabilities are readable and match the registry id")
    void exposesCapabilities() {
        DriverCapabilities capabilities = driver.capabilities();

        assertEquals(JdbcSqlDriver.ID, capabilities.id());
        assertTrue(capabilities.supportsSchemaTree());
        assertEquals(JdbcSqlDriver.MAX_ROWS, capabilities.maxRowsPerQuery());
    }

    @Test
    @DisplayName("connecting opens a usable pool and remembers the configuration")
    void connectOpensPool() {
        assertTrue(driver.isConnected());
        assertTrue(driver.currentConfig().isPresent());
        assertEquals(ConnectionConfig.Driver.H2_MEMORY, driver.currentConfig().orElseThrow().driver());
    }

    @Test
    @DisplayName("testConnection reports the server without disturbing the live pool")
    void testConnectionDescribesServer() throws Exception {
        String description = driver.testConnection(H2TestSupport.freshDatabase())
                .get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertTrue(description.toUpperCase().contains("H2"), "got " + description);
        assertTrue(driver.isConnected(), "the existing connection must survive a test");
    }

    @Test
    @DisplayName("testConnection fails the future for an unreachable endpoint")
    void testConnectionFailsForUnreachableServer() {
        var unreachable = ConnectionConfig.mysql("localhost", 3306, "nope", "nobody", "wrong");

        assertThrows(ExecutionException.class,
                () -> driver.testConnection(unreachable).get(TIMEOUT_SECONDS, TIMEOUT_UNIT));
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
    @DisplayName("large result sets are capped at the advertised maximum")
    void largeResultSetIsCapped() throws Exception {
        int cap = driver.capabilities().maxRowsPerQuery();

        QueryResult result = run("SELECT X FROM SYSTEM_RANGE(1, " + (cap + 500) + ")");

        assertFalse(result.isError(), result.errorMessage());
        assertEquals(cap, result.rowCount());
    }

    @Test
    @DisplayName("invalid SQL resolves to a failed result rather than a failed future")
    void invalidSqlReturnsErrorResult() throws Exception {
        QueryResult result = run("SELECT * FROM table_that_does_not_exist");

        assertTrue(result.isError());
        assertNotNull(result.errorMessage());
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
        ((JdbcSqlDriver) driver).disconnect();
        assertFalse(driver.isConnected());

        QueryResult result = run("SELECT 1");

        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("Not connected"));
    }

    @Test
    @DisplayName("connecting with bad credentials fails the future")
    void badConnectionFailsFuture() {
        try (DataSourceDriver broken = new JdbcSqlDriver()) {
            var config = ConnectionConfig.mysql("localhost", 3306, "nope", "nobody", "wrong");

            assertThrows(ExecutionException.class,
                    () -> broken.connect(config).get(TIMEOUT_SECONDS, TIMEOUT_UNIT));
        }
    }

    private QueryResult run(String sql) throws Exception {
        return driver.executeQueryAsync(sql).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
    }
}
