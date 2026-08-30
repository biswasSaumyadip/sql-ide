package com.lazaro.sqlide.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_SECONDS;
import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_UNIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSqlDriverTransactionTest {

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
    @DisplayName("manual mode rolls back uncommitted inserts")
    void rollbackDiscardsInserts() throws Exception {
        run("CREATE TABLE widget (id INT PRIMARY KEY)");
        driver.setAutoCommit(false).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        assertFalse(driver.isAutoCommit());

        run("INSERT INTO widget VALUES (1)");
        driver.rollback().get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        driver.setAutoCommit(true).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        QueryResult result = run("SELECT COUNT(*) AS c FROM widget");
        assertFalse(result.isError(), result.errorMessage());
        assertEquals("0", result.rows().getFirst().getFirst());
    }

    @Test
    @DisplayName("manual mode keeps inserts after commit")
    void commitPersistsInserts() throws Exception {
        run("CREATE TABLE widget (id INT PRIMARY KEY)");
        driver.beginTransaction().get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        run("INSERT INTO widget VALUES (7)");
        driver.commit().get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        QueryResult result = run("SELECT id FROM widget");
        assertFalse(result.isError(), result.errorMessage());
        assertEquals("7", result.rows().getFirst().getFirst());
    }

    @Test
    @DisplayName("cancelExecution stops a long-running statement")
    void cancelStopsLongQuery() throws Exception {
        // Nested ranges stay busy long enough for Statement.cancel to land.
        CompletableFuture<QueryResult> running = driver.executeQueryAsync(
                "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 20000) A, SYSTEM_RANGE(1, 20000) B");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!driver.isExecuting() && !running.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(driver.isExecuting(), "driver should expose the in-flight statement");

        driver.cancelExecution().get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        QueryResult result = running.get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertTrue(result.isError(), "expected cancel error, got success: " + result.summary());
        String message = result.errorMessage().toLowerCase();
        assertTrue(message.contains("cancel")
                        || message.contains("interrupt")
                        || message.contains("closed")
                        || message.contains("abort"),
                result.errorMessage());
    }

    private QueryResult run(String sql) throws Exception {
        return driver.executeQueryAsync(sql).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
    }
}
