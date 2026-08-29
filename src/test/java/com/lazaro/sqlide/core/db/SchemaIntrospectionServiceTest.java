package com.lazaro.sqlide.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_SECONDS;
import static com.lazaro.sqlide.core.db.H2TestSupport.TIMEOUT_UNIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaIntrospectionServiceTest {

    private DatabaseService databaseService;
    private SchemaIntrospectionService schemaService;
    private String catalog;

    @BeforeEach
    void connectAndSeed() throws Exception {
        databaseService = new DatabaseService();
        databaseService.connectAsync(H2TestSupport.freshDatabase()).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        schemaService = new SchemaIntrospectionService(databaseService);

        execute("""
                CREATE TABLE customer (
                    id          INT           PRIMARY KEY,
                    email       VARCHAR(255)  NOT NULL,
                    balance     DECIMAL(12,2),
                    signed_up   TIMESTAMP
                )
                """);
        execute("CREATE VIEW premium_customer AS SELECT id, email FROM customer WHERE balance > 100");

        catalog = schemaService.fetchDatabasesAsync().get(TIMEOUT_SECONDS, TIMEOUT_UNIT)
                .stream()
                .map(DatabaseNode::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("sqlide_test_"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("test catalog was not reported by getCatalogs()"));
    }

    @AfterEach
    void disconnect() {
        databaseService.close();
    }

    @Test
    @DisplayName("catalogs are discovered and returned sorted, without tables attached")
    void discoversCatalogs() throws Exception {
        List<DatabaseNode> databases = schemaService.fetchDatabasesAsync().get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertFalse(databases.isEmpty());
        assertTrue(databases.stream().allMatch(database -> database.tables().isEmpty()),
                "catalog listing must stay shallow so the tree can load lazily");
    }

    @Test
    @DisplayName("tables and views are listed, system objects are not")
    void listsTablesAndViews() throws Exception {
        List<TableNode> tables = schemaService.fetchTablesAsync(catalog).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        List<String> names = tables.stream().map(TableNode::name).toList();
        assertTrue(names.contains("CUSTOMER"), "expected CUSTOMER in " + names);
        assertTrue(names.contains("PREMIUM_CUSTOMER"), "expected the view in " + names);
        assertFalse(names.contains("TABLES"), "INFORMATION_SCHEMA objects must be filtered out: " + names);

        TableNode view = tables.stream().filter(t -> t.name().equals("PREMIUM_CUSTOMER")).findFirst().orElseThrow();
        assertTrue(view.isView());
    }

    @Test
    @DisplayName("columns carry type, nullability, ordering and primary key flags")
    void describesColumns() throws Exception {
        List<ColumnNode> columns =
                schemaService.fetchColumnsAsync(catalog, "CUSTOMER").get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertEquals(List.of("ID", "EMAIL", "BALANCE", "SIGNED_UP"), columns.stream().map(ColumnNode::name).toList());

        ColumnNode id = columns.get(0);
        assertTrue(id.primaryKey());
        assertFalse(id.nullable());
        assertEquals(1, id.position());

        ColumnNode email = columns.get(1);
        assertFalse(email.primaryKey());
        assertFalse(email.nullable());
        assertEquals("CHARACTER VARYING(255)", email.displayType());

        ColumnNode balance = columns.get(2);
        assertTrue(balance.nullable());
        assertEquals("DECIMAL(12,2)", balance.displayType());

        // A plain timestamp must not be decorated with a meaningless size.
        assertEquals("TIMESTAMP", columns.get(3).displayType());
    }

    @Test
    @DisplayName("eager fetch returns one catalog fully populated with tables and columns")
    void eagerFetchPopulatesEverything() throws Exception {
        DatabaseNode database = schemaService.fetchDatabaseAsync(catalog).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertEquals(catalog, database.name());
        assertFalse(database.tables().isEmpty());
        TableNode customer = database.tables().stream()
                .filter(table -> table.name().equals("CUSTOMER"))
                .findFirst()
                .orElseThrow();
        assertEquals(4, customer.columns().size());
        assertEquals(catalog + ".CUSTOMER", customer.qualifiedName());
    }

    private void execute(String sql) throws Exception {
        QueryResult result = databaseService.executeQueryAsync(sql).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        if (result.isError()) {
            throw new AssertionError("seed statement failed: " + result.errorMessage());
        }
    }
}
