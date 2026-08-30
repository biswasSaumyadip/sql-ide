package com.lazaro.sqlide.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_SECONDS;
import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_UNIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSqlDriverScriptTest {

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
    @DisplayName("script execution returns one result per statement")
    void runsMultipleStatements() throws Exception {
        ScriptResult script = driver.executeScriptAsync(List.of(
                        "CREATE TABLE widget (id INT PRIMARY KEY)",
                        "INSERT INTO widget VALUES (1), (2)",
                        "SELECT id FROM widget ORDER BY id"))
                .get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertEquals(3, script.results().size());
        assertFalse(script.stoppedEarly());
        assertEquals(2, script.results().get(2).rowCount());
    }

    @Test
    @DisplayName("script stops after the first error but keeps prior successes")
    void stopsOnError() throws Exception {
        ScriptResult script = driver.executeScriptAsync(List.of(
                        "CREATE TABLE widget (id INT PRIMARY KEY)",
                        "INSERT INTO widget VALUES (1)",
                        "SELECT * FROM missing_table",
                        "SELECT 1"))
                .get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertEquals(3, script.results().size());
        assertTrue(script.stoppedEarly());
        assertTrue(script.results().get(2).isError());
    }
}
