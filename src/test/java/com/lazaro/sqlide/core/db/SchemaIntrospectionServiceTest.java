package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
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

    private JdbcSqlDriver driver;
    private SchemaIntrospectionService schemaService;
    private String catalog;

    @BeforeEach
    void connectAndSeed() throws Exception {
        driver = new JdbcSqlDriver();
        driver.connect(H2TestSupport.freshDatabase()).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        schemaService = driver.introspection();

        execute("""
                CREATE TABLE customer (
                    id          INT           PRIMARY KEY,
                    email       VARCHAR(255)  NOT NULL,
                    balance     DECIMAL(12,2),
                    signed_up   TIMESTAMP
                )
                """);
        execute("""
                CREATE TABLE orders (
                    id          INT PRIMARY KEY,
                    customer_id INT NOT NULL,
                    total       DECIMAL(12,2),
                    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
                )
                """);
        execute("CREATE VIEW premium_customer AS SELECT id, email FROM customer WHERE balance > 100");
        execute("CREATE INDEX idx_customer_email ON customer(email)");

        catalog = driver.getSchemaTree().get(TIMEOUT_SECONDS, TIMEOUT_UNIT)
                .stream()
                .map(SchemaNode::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("sqlide_test_"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("test catalog was not reported by getCatalogs()"));
    }

    @AfterEach
    void disconnect() {
        driver.close();
    }

    @Test
    @DisplayName("the schema tree starts at catalogs, unexpanded")
    void schemaTreeStartsAtCatalogs() throws Exception {
        List<SchemaNode> databases = driver.getSchemaTree().get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertFalse(databases.isEmpty());
        assertTrue(databases.stream().allMatch(node -> node.type() == NodeType.DATABASE),
                "H2 exposes databases as catalogs");
        assertTrue(databases.stream().allMatch(node -> node.children().isEmpty()),
                "the top level must stay shallow so the tree can load lazily");
        assertTrue(databases.stream().noneMatch(SchemaNode::isLeaf));
    }

    @Test
    @DisplayName("tables and views are listed with the right node type, system objects are not")
    void listsTablesAndViews() throws Exception {
        List<SchemaNode> tables = schemaService.fetchTablesAsync(catalog).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        List<String> names = tables.stream().map(SchemaNode::name).toList();
        assertTrue(names.contains("CUSTOMER"), "expected CUSTOMER in " + names);
        assertTrue(names.contains("PREMIUM_CUSTOMER"), "expected the view in " + names);
        assertFalse(names.contains("TABLES"), "INFORMATION_SCHEMA objects must be filtered out: " + names);

        SchemaNode table = find(tables, "CUSTOMER");
        assertEquals(NodeType.TABLE, table.type());
        assertEquals(catalog, table.metadata(SchemaNode.META_CATALOG));
        assertEquals(catalog + ".CUSTOMER", table.qualifiedName());

        assertEquals(NodeType.VIEW, find(tables, "PREMIUM_CUSTOMER").type());
    }

    @Test
    @DisplayName("columns carry type, nullability, ordering and primary key metadata")
    void describesColumns() throws Exception {
        List<SchemaNode> columns =
                schemaService.fetchColumnsAsync(catalog, "CUSTOMER").get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertEquals(List.of("ID", "EMAIL", "BALANCE", "SIGNED_UP"), columns.stream().map(SchemaNode::name).toList());
        assertTrue(columns.stream().allMatch(SchemaNode::isLeaf));

        SchemaNode id = columns.get(0);
        assertEquals(NodeType.COLUMN, id.type());
        assertTrue(id.metadataFlag(SchemaNode.META_PRIMARY_KEY));
        assertFalse(id.metadataFlag(SchemaNode.META_NULLABLE));

        SchemaNode email = columns.get(1);
        assertFalse(email.metadataFlag(SchemaNode.META_PRIMARY_KEY));
        assertFalse(email.metadataFlag(SchemaNode.META_NULLABLE));
        assertEquals("CHARACTER VARYING(255)", email.metadata(SchemaNode.META_DATA_TYPE));

        SchemaNode balance = columns.get(2);
        assertTrue(balance.metadataFlag(SchemaNode.META_NULLABLE));
        assertEquals("DECIMAL(12,2)", balance.metadata(SchemaNode.META_DATA_TYPE));

        // A plain timestamp must not be decorated with a meaningless size.
        assertEquals("TIMESTAMP", columns.get(3).metadata(SchemaNode.META_DATA_TYPE));
    }

    @Test
    @DisplayName("getChildren walks the tree through logical folders")
    void getChildrenWalksTheTree() throws Exception {
        SchemaNode database = find(driver.getSchemaTree().get(TIMEOUT_SECONDS, TIMEOUT_UNIT), catalog);

        List<SchemaNode> folders = driver.getChildren(database).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        assertTrue(folders.stream().anyMatch(node -> node.type() == NodeType.FOLDER
                && SchemaNode.FOLDER_TABLES.equals(node.folderKind())));
        SchemaNode tablesFolder = folders.stream()
                .filter(node -> SchemaNode.FOLDER_TABLES.equals(node.folderKind()))
                .findFirst()
                .orElseThrow();
        assertTrue(tablesFolder.childCountBadge() >= 1);

        SchemaNode customer = find(tablesFolder.children().isEmpty()
                ? driver.getChildren(tablesFolder).get(TIMEOUT_SECONDS, TIMEOUT_UNIT)
                : tablesFolder.children(), "CUSTOMER");

        List<SchemaNode> tableFolders = driver.getChildren(customer).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        assertEquals(3, tableFolders.size());
        assertEquals(List.of("columns", "keys", "indexes"),
                tableFolders.stream().map(SchemaNode::name).toList());

        SchemaNode columnsFolder = tableFolders.get(0);
        List<SchemaNode> columns = columnsFolder.children().isEmpty()
                ? driver.getChildren(columnsFolder).get(TIMEOUT_SECONDS, TIMEOUT_UNIT)
                : columnsFolder.children();
        assertEquals(4, columns.size());

        SchemaNode keysFolder = tableFolders.get(1);
        List<SchemaNode> keys = keysFolder.children().isEmpty()
                ? driver.getChildren(keysFolder).get(TIMEOUT_SECONDS, TIMEOUT_UNIT)
                : keysFolder.children();
        assertTrue(keys.stream().anyMatch(node ->
                node.type() == NodeType.KEY && "PRIMARY".equalsIgnoreCase(node.name())));

        SchemaNode indexesFolder = tableFolders.get(2);
        List<SchemaNode> indexes = indexesFolder.children().isEmpty()
                ? driver.getChildren(indexesFolder).get(TIMEOUT_SECONDS, TIMEOUT_UNIT)
                : indexesFolder.children();
        assertTrue(indexes.stream().anyMatch(node -> node.type() == NodeType.INDEX));

        assertTrue(driver.getChildren(columns.get(0)).get(TIMEOUT_SECONDS, TIMEOUT_UNIT).isEmpty(),
                "a column has no children");
    }

    @Test
    @DisplayName("eager fetch returns one catalog fully populated")
    void eagerFetchPopulatesEverything() throws Exception {
        SchemaNode database = schemaService.fetchDatabaseAsync(catalog).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);

        assertEquals(catalog, database.name());
        assertEquals(NodeType.DATABASE, database.type());
        assertEquals(4, find(database.children(), "CUSTOMER").children().size());
    }

    @Test
    @DisplayName("schema outline lists table names without loading columns")
    void schemaOutlineIsNamesOnly() throws Exception {
        List<SchemaNode> outline = schemaService.fetchSchemaOutlineAsync().get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        SchemaNode database = find(outline, catalog);
        SchemaNode customer = find(database.children(), "CUSTOMER");

        assertTrue(customer.children().isEmpty(),
                "outline must stay names-only so large catalogs can autocomplete tables immediately");
        assertTrue(database.children().stream().anyMatch(node ->
                node.type() == NodeType.TABLE && node.name().equals("CUSTOMER")));
        assertTrue(database.children().stream().anyMatch(node ->
                node.type() == NodeType.VIEW && node.name().equals("PREMIUM_CUSTOMER")));
    }

    @Test
    @DisplayName("preferred catalog is outlined first; unknown preferred falls back to every catalog")
    void schemaOutlinePrefersNamedCatalog() throws Exception {
        List<SchemaNode> preferred = schemaService.fetchSchemaOutlineAsync(catalog)
                .get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        assertFalse(preferred.isEmpty());
        assertTrue(preferred.getFirst().name().equalsIgnoreCase(catalog),
                "active catalog should be first, got " + preferred.getFirst().name());
        SchemaNode active = find(preferred, catalog);
        assertTrue(active.children().stream().anyMatch(node -> node.name().equals("CUSTOMER")));

        for (SchemaNode database : preferred) {
            if (database.name().equalsIgnoreCase(catalog)) {
                continue;
            }
            assertTrue(database.children().isEmpty(),
                    "other catalogs stay shells until the secondary pass: " + database.name());
        }

        List<SchemaNode> fallback = schemaService.fetchSchemaOutlineAsync("no_such_database")
                .get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        assertTrue(find(fallback, catalog).children().stream().anyMatch(node -> node.name().equals("CUSTOMER")),
                "unknown preferred catalog must still load every database");
    }

    @Test
    @DisplayName("secondary schema omits the preferred catalog")
    void secondarySchemaSkipsPreferredCatalog() throws Exception {
        List<SchemaNode> secondary = schemaService.fetchSecondarySchemaAsync(catalog)
                .get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        assertTrue(secondary.stream().noneMatch(node -> node.name().equalsIgnoreCase(catalog)),
                secondary.stream().map(SchemaNode::name).toList().toString());
    }

    @Test
    @DisplayName("full schema packs FK, index and DDL metadata onto table nodes")
    void fullSchemaEnrichesTableMetadata() throws Exception {
        List<SchemaNode> full = driver.getFullSchema().get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        SchemaNode database = find(full, catalog);
        SchemaNode orders = find(database.children(), "ORDERS");

        assertFalse(orders.children().isEmpty(), "columns should be attached");
        String fks = orders.metadata(SchemaNode.META_FOREIGN_KEYS);
        assertTrue(fks != null && fks.contains("CUSTOMER"), "expected FK metadata, got: " + fks);
        assertTrue(fks.contains("CUSTOMER_ID"));

        SchemaNode customer = find(database.children(), "CUSTOMER");
        String indexes = customer.metadata(SchemaNode.META_INDEXES);
        assertTrue(indexes != null && indexes.toUpperCase(Locale.ROOT).contains("EMAIL"),
                "expected email index in: " + indexes);

        String ddl = customer.metadata(SchemaNode.META_DDL);
        assertTrue(ddl != null && ddl.toUpperCase(Locale.ROOT).contains("CREATE TABLE"),
                "expected generated DDL, got: " + ddl);
    }

    @Test
    @DisplayName("type formatting only adds a size where it means something")
    void formatsTypes() {
        assertEquals("VARCHAR(255)", SchemaIntrospectionService.formatType("VARCHAR", 255, 0));
        assertEquals("DECIMAL(10,2)", SchemaIntrospectionService.formatType("DECIMAL", 10, 2));
        assertEquals("INT", SchemaIntrospectionService.formatType("INT", 10, 0));
        assertEquals("TIMESTAMP", SchemaIntrospectionService.formatType("TIMESTAMP", 26, 6));
        assertEquals("UNKNOWN", SchemaIntrospectionService.formatType(null, 0, 0));
    }

    @Test
    @DisplayName("routine bodies wrap as CREATE PROCEDURE when the catalog omits the header")
    void wrapRoutineDefinition() {
        String body = """
                BEGIN
                  DECLARE i INT DEFAULT 1;
                  WHILE i <= 1050 DO
                    SET i = i + 1;
                  END WHILE;
                END
                """.strip();
        String ddl = SchemaIntrospectionService.wrapRoutineDefinition("PROCEDURE", "InsertDummyData", body);
        assertTrue(ddl.startsWith("CREATE PROCEDURE InsertDummyData()"));
        assertTrue(ddl.contains("WHILE i <= 1050 DO"));
        assertEquals(
                "CREATE PROCEDURE already()",
                SchemaIntrospectionService.wrapRoutineDefinition(
                        "PROCEDURE", "ignored", "CREATE PROCEDURE already()"));
        assertEquals("CREATE FUNCTION fn()", SchemaIntrospectionService.wrapRoutineDefinition("FUNCTION", "fn", ""));
    }

    private static SchemaNode find(List<SchemaNode> nodes, String name) {
        return nodes.stream()
                .filter(node -> node.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node named " + name + " in "
                        + nodes.stream().map(SchemaNode::name).toList()));
    }

    private void execute(String sql) throws Exception {
        QueryResult result = driver.executeQueryAsync(sql).get(TIMEOUT_SECONDS, TIMEOUT_UNIT);
        if (result.isError()) {
            throw new AssertionError("seed statement failed: " + result.errorMessage());
        }
    }
}
