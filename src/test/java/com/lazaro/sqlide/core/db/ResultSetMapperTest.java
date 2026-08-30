package com.lazaro.sqlide.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultSetMapperTest {

    private Connection connection;

    @BeforeEach
    void open() throws Exception {
        String url = "jdbc:h2:mem:trunc_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE numbers (n INT)");
            for (int i = 1; i <= 25; i++) {
                statement.execute("INSERT INTO numbers VALUES (" + i + ")");
            }
        }
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void marksTruncatedWhenMoreRowsExist() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT n FROM numbers ORDER BY n")) {
            QueryResult result = ResultSetMapper.drain(resultSet, 10, System.nanoTime());
            assertEquals(10, result.rowCount());
            assertTrue(result.truncated());
            assertEquals("1", result.rows().getFirst().getFirst());
            assertEquals("10", result.rows().getLast().getFirst());
        }
    }

    @Test
    void notTruncatedWhenAllRowsFit() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT n FROM numbers ORDER BY n")) {
            QueryResult result = ResultSetMapper.drain(resultSet, 100, System.nanoTime());
            assertEquals(25, result.rowCount());
            assertFalse(result.truncated());
        }
    }
}
