package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_SECONDS;
import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_UNIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end check against a real MySQL server, driven entirely through
 * {@link DataSourceDriver} and {@link DriverRegistry}.
 *
 * <p>Skipped when no server answers, so the normal build stays self-contained.
 * Point it at a server with {@code -Dsqlide.mysql.port=3306} and friends.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MySqlIntegrationTest {

    private static final String HOST = System.getProperty("sqlide.mysql.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("sqlide.mysql.port", 3307);
    private static final String USER = System.getProperty("sqlide.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("sqlide.mysql.password", "");
    private static final String SCHEMA = "sqlide_it";

    private static DataSourceDriver driver;

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue(reachable(), "no MySQL server on %s:%d, skipping".formatted(HOST, PORT));

        driver = DriverRegistry.withDefaults().create(JdbcSqlDriver.ID);
        driver.connect(config("")).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        run("DROP DATABASE IF EXISTS " + SCHEMA);
        run("CREATE DATABASE " + SCHEMA);
        run("""
                CREATE TABLE %s.customer (
                    id        INT           NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    email     VARCHAR(255)  NOT NULL,
                    balance   DECIMAL(12,2) NULL,
                    signed_up TIMESTAMP     NULL
                )
                """.formatted(SCHEMA));
        run("CREATE VIEW %s.rich_customer AS SELECT id, email FROM %s.customer WHERE balance > 100"
                .formatted(SCHEMA, SCHEMA));
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (driver != null) {
            run("DROP DATABASE IF EXISTS " + SCHEMA);
            driver.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("the registry-built driver reports the real MySQL server")
    void reportsServer() throws Exception {
        String description = driver.testConnection(config(SCHEMA)).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertTrue(description.startsWith("MySQL"), "got " + description);
        assertTrue(driver.isConnected());
        assertEquals(JdbcSqlDriver.ID, driver.capabilities().id());
    }

    @Test
    @Order(2)
    @DisplayName("DML and SELECT round-trip through the interface")
    void writesAndReadsRows() throws Exception {
        QueryResult inserted = run("""
                INSERT INTO %s.customer (email, balance) VALUES
                    ('ada@example.com', 4820.50),
                    ('grace@example.com', 3110.00),
                    ('alan@example.com', NULL)
                """.formatted(SCHEMA));
        assertFalse(inserted.isError(), inserted.errorMessage());
        assertFalse(inserted.isResultSet());
        assertEquals(3, inserted.rowCount());

        QueryResult selected = run(
                "SELECT id, email, balance FROM %s.customer ORDER BY id".formatted(SCHEMA));
        assertFalse(selected.isError(), selected.errorMessage());
        assertTrue(selected.isResultSet());
        assertEquals(List.of("id", "email", "balance"), selected.columnNames());
        assertEquals(3, selected.rowCount());
        assertEquals("ada@example.com", selected.rows().get(0).get(1));
        assertEquals("4820.50", selected.rows().get(0).get(2));
        assertEquals(null, selected.rows().get(2).get(2), "MySQL NULL must arrive as a null cell");
    }

    @Test
    @Order(3)
    @DisplayName("a rejected statement resolves to a failed result carrying MySQL's error")
    void reportsSqlErrors() throws Exception {
        QueryResult result = run("SELECT * FROM %s.does_not_exist".formatted(SCHEMA));

        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("SQLState"), "got " + result.errorMessage());
    }

    @Test
    @Order(4)
    @DisplayName("the schema tree lists MySQL databases")
    void listsDatabases() throws Exception {
        List<SchemaNode> databases = driver.getSchemaTree().get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertTrue(databases.stream().anyMatch(node -> node.name().equals(SCHEMA)),
                "expected " + SCHEMA + " in " + databases.stream().map(SchemaNode::name).toList());
        assertTrue(databases.stream().allMatch(node -> node.type() == NodeType.DATABASE));
    }

    @Test
    @Order(5)
    @DisplayName("expanding a database yields logical folders, tables, then typed columns")
    void walksTheTree() throws Exception {
        SchemaNode database = driver.getSchemaTree().get(TIMEOUT_SECONDS, TIMEOUT_UNIT).stream()
                .filter(node -> node.name().equals(SCHEMA))
                .findFirst()
                .orElseThrow();

        List<SchemaNode> folders = driver.getChildren(database).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        SchemaNode tablesFolder = folders.stream()
                .filter(node -> SchemaNode.FOLDER_TABLES.equals(node.folderKind()))
                .findFirst()
                .orElseThrow();
        List<SchemaNode> tables = tablesFolder.children().isEmpty()
                ? driver.getChildren(tablesFolder).get(TIMEOUT_SECONDS, TIMEOUT_UNIT)
                : tablesFolder.children();
        SchemaNode customer = find(tables, "customer");
        assertEquals(NodeType.TABLE, customer.type());
        SchemaNode viewsFolder = folders.stream()
                .filter(node -> SchemaNode.FOLDER_VIEWS.equals(node.folderKind()))
                .findFirst()
                .orElse(null);
        if (viewsFolder != null) {
            List<SchemaNode> views = viewsFolder.children().isEmpty()
                    ? driver.getChildren(viewsFolder).get(TIMEOUT_SECONDS, TIMEOUT_UNIT)
                    : viewsFolder.children();
            assertEquals(NodeType.VIEW, find(views, "rich_customer").type());
        }
        assertEquals(SCHEMA + ".customer", customer.qualifiedName());

        List<SchemaNode> tableFolders = driver.getChildren(customer).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        SchemaNode columnsFolder = tableFolders.stream()
                .filter(node -> SchemaNode.FOLDER_COLUMNS.equals(node.folderKind()))
                .findFirst()
                .orElseThrow();
        List<SchemaNode> columns = columnsFolder.children().isEmpty()
                ? driver.getChildren(columnsFolder).get(TIMEOUT_SECONDS, TIMEOUT_UNIT)
                : columnsFolder.children();
        assertEquals(List.of("id", "email", "balance", "signed_up"),
                columns.stream().map(SchemaNode::name).toList());

        SchemaNode id = columns.get(0);
        assertTrue(id.metadataFlag(SchemaNode.META_PRIMARY_KEY));
        assertFalse(id.metadataFlag(SchemaNode.META_NULLABLE));

        assertEquals("VARCHAR(255)", columns.get(1).metadata(SchemaNode.META_DATA_TYPE));
        assertEquals("DECIMAL(12,2)", columns.get(2).metadata(SchemaNode.META_DATA_TYPE));
        assertTrue(columns.get(2).metadataFlag(SchemaNode.META_NULLABLE));
        assertEquals("TIMESTAMP", columns.get(3).metadata(SchemaNode.META_DATA_TYPE));
    }

    // ---------------------------------------------------------------- helpers

    private static ConnectionConfig config(String database) {
        return ConnectionConfig.mysql(HOST, PORT, database, USER, PASSWORD);
    }

    private static QueryResult run(String sql) throws Exception {
        QueryResult result = driver.executeQueryAsync(sql).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        return result;
    }

    private static SchemaNode find(List<SchemaNode> nodes, String name) {
        return nodes.stream()
                .filter(node -> node.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node named " + name + " in "
                        + nodes.stream().map(SchemaNode::name).toList()));
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1_500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
