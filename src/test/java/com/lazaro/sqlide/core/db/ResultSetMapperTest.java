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
    void skipsAlreadyLoadedRowsThenMarksTruncation() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT n FROM numbers ORDER BY n")) {
            QueryResult result = ResultSetMapper.drain(resultSet, 10, 5, System.nanoTime());
            assertEquals(5, result.rowCount());
            assertEquals("11", result.rows().getFirst().getFirst());
            assertEquals("15", result.rows().getLast().getFirst());
            assertTrue(result.truncated());
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

    @Test
    void capturesTypeAndKeyFlagsFromJdbcMetadata() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE parent (
                      id INT PRIMARY KEY,
                      label VARCHAR(32)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE child (
                      id INT PRIMARY KEY,
                      parent_id INT,
                      created_at TIMESTAMP,
                      FOREIGN KEY (parent_id) REFERENCES parent(id)
                    )
                    """);
            statement.execute("INSERT INTO parent VALUES (1, 'root')");
            statement.execute("INSERT INTO child VALUES (10, 1, CURRENT_TIMESTAMP)");
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT id, parent_id, created_at FROM child")) {
            QueryResult result = ResultSetMapper.drain(resultSet, 10, System.nanoTime());
            assertEquals(3, result.columns().size());
            ResultColumn id = result.columns().get(0);
            ResultColumn parentId = result.columns().get(1);
            ResultColumn created = result.columns().get(2);
            assertTrue(id.name().equalsIgnoreCase("id"));
            assertTrue(id.primaryKey(), "id should be marked as a primary key");
            assertEquals(ResultColumn.Kind.NUMERIC, id.kind());
            assertEquals("123", id.typeBadge());
            assertTrue(parentId.name().equalsIgnoreCase("parent_id"));
            assertTrue(parentId.foreignKey(), "parent_id should be marked as a foreign key");
            assertEquals(ResultColumn.Kind.TEMPORAL, created.kind());
            assertEquals("", created.typeBadge());
        }
    }
}
