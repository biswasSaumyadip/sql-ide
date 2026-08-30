package com.lazaro.sqlide.core.inspection;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlInspectorTest {

    private SchemaCache cache;

    @BeforeEach
    void seed() {
        SchemaNode id = SchemaNode.of("id", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode email = SchemaNode.of("email", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "VARCHAR"));
        SchemaNode users = new SchemaNode("users", NodeType.TABLE, List.of(id, email), Map.of(
                SchemaNode.META_CATALOG, "app"));

        SchemaNode userId = SchemaNode.of("user_id", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode total = SchemaNode.of("total", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode orders = new SchemaNode("orders", NodeType.TABLE, List.of(userId, total), Map.of(
                SchemaNode.META_CATALOG, "app"));

        cache = new SchemaCache();
        cache.replace(List.of(new SchemaNode("app", NodeType.DATABASE, List.of(users, orders), Map.of())));
    }

    @Test
    @DisplayName("syntax errors become ERROR issues")
    void flagsSyntaxErrors() {
        List<InspectionIssue> issues = SqlInspector.inspect("SELECT FROM", cache, "app");
        assertTrue(issues.stream().anyMatch(i -> i.severity() == Severity.ERROR));
    }

    @Test
    @DisplayName("UPDATE/DELETE without WHERE is a WARNING")
    void flagsUnsafeDml() {
        String updateSql = "UPDATE users SET email = 'x'";
        List<InspectionIssue> updates = SqlInspector.inspect(updateSql, cache, "app");
        InspectionIssue update = updates.stream()
                .filter(i -> i.severity() == Severity.WARNING && i.message().contains("without WHERE"))
                .findFirst()
                .orElseThrow();
        assertEquals("UPDATE", updateSql.substring(update.startOffset(), update.endOffset()));

        String deleteSql = "DELETE FROM users";
        List<InspectionIssue> deletes = SqlInspector.inspect(deleteSql, cache, "app");
        InspectionIssue delete = deletes.stream()
                .filter(i -> i.severity() == Severity.WARNING && i.message().contains("without WHERE"))
                .findFirst()
                .orElseThrow();
        assertEquals("DELETE", deleteSql.substring(delete.startOffset(), delete.endOffset()));
    }

    @Test
    @DisplayName("unknown tables and columns are ERRORs")
    void flagsUnknownIdentifiers() {
        List<InspectionIssue> tables = SqlInspector.inspect("SELECT 1 FROM missing_table", cache, "app");
        assertTrue(tables.stream().anyMatch(i -> i.message().contains("Unknown table")));

        String sql = "SELECT nope FROM users";
        List<InspectionIssue> columns = SqlInspector.inspect(sql, cache, "app");
        InspectionIssue unknown = columns.stream()
                .filter(i -> i.message().contains("Unknown column"))
                .findFirst()
                .orElseThrow();
        assertEquals("nope", sql.substring(unknown.startOffset(), unknown.endOffset()));
    }

    @Test
    @DisplayName("unqualified columns owned by multiple tables are ambiguous")
    void flagsAmbiguousColumns() {
        // invent a shared column name across both tables for this case
        SchemaNode sharedUsers = new SchemaNode("users", NodeType.TABLE, List.of(
                SchemaNode.of("id", NodeType.COLUMN, Map.of()),
                SchemaNode.of("name", NodeType.COLUMN, Map.of())), Map.of(SchemaNode.META_CATALOG, "app"));
        SchemaNode sharedOrders = new SchemaNode("orders", NodeType.TABLE, List.of(
                SchemaNode.of("id", NodeType.COLUMN, Map.of()),
                SchemaNode.of("name", NodeType.COLUMN, Map.of())), Map.of(SchemaNode.META_CATALOG, "app"));
        cache.replace(List.of(new SchemaNode("app", NodeType.DATABASE, List.of(sharedUsers, sharedOrders), Map.of())));

        List<InspectionIssue> issues = SqlInspector.inspect(
                "SELECT name FROM users JOIN orders ON users.id = orders.id", cache, "app");
        assertTrue(issues.stream().anyMatch(i -> i.message().equals("Ambiguous column reference")));
    }

    @Test
    @DisplayName("GROUP BY mismatch is an ERROR")
    void flagsGroupByMismatch() {
        List<InspectionIssue> issues = SqlInspector.inspect(
                "SELECT email, COUNT(*) FROM users GROUP BY id", cache, "app");
        assertTrue(issues.stream().anyMatch(i ->
                i.severity() == Severity.ERROR && i.message().toLowerCase().contains("group by")));
    }

    @Test
    @DisplayName("constant conditions are WEAK_WARNINGs")
    void flagsConstantConditions() {
        List<InspectionIssue> tautology = SqlInspector.inspect(
                "SELECT * FROM users WHERE 1 = 1", cache, "app");
        assertTrue(tautology.stream().anyMatch(i -> i.severity() == Severity.WEAK_WARNING));

        List<InspectionIssue> nullCmp = SqlInspector.inspect(
                "SELECT * FROM users WHERE email = NULL", cache, "app");
        assertTrue(nullCmp.stream().anyMatch(i ->
                i.severity() == Severity.WEAK_WARNING && i.message().toUpperCase().contains("NULL")));
    }

    @Test
    @DisplayName("INSERT / UPDATE literal type mismatches are WARNINGs")
    void flagsInsertTypeMismatch() {
        SchemaNode id = SchemaNode.of("id", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode name = SchemaNode.of("name", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "VARCHAR(64)"));
        SchemaNode race = new SchemaNode("race", NodeType.TABLE, List.of(id, name), Map.of(
                SchemaNode.META_CATALOG, "app"));
        cache.replace(List.of(new SchemaNode("app", NodeType.DATABASE, List.of(race), Map.of())));

        String sql = "INSERT INTO race (id, name) VALUES (1, 2)";
        List<InspectionIssue> issues = SqlInspector.inspect(sql, cache, "app");
        InspectionIssue mismatch = issues.stream()
                .filter(i -> i.message().contains("Type mismatch") && i.message().contains("name"))
                .findFirst()
                .orElseThrow();
        assertEquals(Severity.WARNING, mismatch.severity());
        assertEquals("2", sql.substring(mismatch.startOffset(), mismatch.endOffset()));

        List<InspectionIssue> ok = SqlInspector.inspect(
                "INSERT INTO race (id, name) VALUES (1, 'Human')", cache, "app");
        assertTrue(ok.stream().noneMatch(i -> i.message().contains("Type mismatch")));

        List<InspectionIssue> updates = SqlInspector.inspect(
                "UPDATE race SET name = 2 WHERE id = 1", cache, "app");
        assertTrue(updates.stream().anyMatch(i ->
                i.severity() == Severity.WARNING && i.message().contains("Type mismatch")));
    }

    @Test
    @DisplayName("valid queries produce no schema errors")
    void acceptsValidQuery() {
        List<InspectionIssue> issues = SqlInspector.inspect(
                "SELECT u.email FROM users u WHERE u.id = 1", cache, "app");
        assertEquals(0, issues.stream().filter(i -> i.severity() == Severity.ERROR).count());
    }

    @Test
    @DisplayName("fully qualified catalog.table resolves hierarchically")
    void acceptsFullyQualifiedTable() {
        SchemaNode id = SchemaNode.of("id", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode name = SchemaNode.of("name", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "VARCHAR"));
        SchemaNode faction = new SchemaNode("faction", NodeType.TABLE, List.of(id, name), Map.of(
                SchemaNode.META_CATALOG, "warcraft"));
        cache.replace(List.of(
                new SchemaNode("app", NodeType.DATABASE, List.of(), Map.of()),
                new SchemaNode("warcraft", NodeType.DATABASE, List.of(faction), Map.of())));

        // Active catalog is app — unqualified would miss, but warcraft.faction must resolve.
        List<InspectionIssue> issues = SqlInspector.inspect(
                "SELECT name FROM warcraft.faction WHERE id = 1", cache, "app");
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("Unknown table")),
                () -> "unexpected issues: " + issues);

        List<InspectionIssue> unknownSchema = SqlInspector.inspect(
                "SELECT 1 FROM missing_db.faction", cache, "app");
        assertTrue(unknownSchema.stream().anyMatch(i -> i.message().contains("Unknown table")));

        List<InspectionIssue> wrongTable = SqlInspector.inspect(
                "SELECT 1 FROM warcraft.missing_table", cache, "app");
        assertTrue(wrongTable.stream().anyMatch(i -> i.message().contains("Unknown table")));
    }

    @Test
    @DisplayName("unqualified tables still resolve against the active catalog")
    void acceptsUnqualifiedInActiveCatalog() {
        List<InspectionIssue> issues = SqlInspector.inspect(
                "SELECT email FROM users", cache, "app");
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("Unknown table")));
    }

    @Test
    @DisplayName("LIKE string literals are not flagged as unknown columns")
    void ignoresLikeStringLiterals() {
        String single = "SELECT email FROM users WHERE email LIKE '%pala%'";
        List<InspectionIssue> singleIssues = SqlInspector.inspect(single, cache, "app");
        assertTrue(singleIssues.stream().noneMatch(i -> i.message().contains("Unknown column")),
                () -> "unexpected: " + singleIssues);

        // JSqlParser treats double-quoted tokens as identifiers; still must not lint LIKE patterns.
        String dbl = "SELECT email FROM users WHERE email LIKE \"%pala%\"";
        List<InspectionIssue> dblIssues = SqlInspector.inspect(dbl, cache, "app");
        assertTrue(dblIssues.stream().noneMatch(i ->
                        i.message().contains("Unknown column") && i.message().contains("pala")),
                () -> "unexpected: " + dblIssues);
    }

    @Test
    @DisplayName("numeric-looking fake columns are skipped")
    void ignoresNumericMasqueradingColumns() {
        assertTrue(SqlInspector.isNonSchemaColumnName("%pala%"));
        assertTrue(SqlInspector.isNonSchemaColumnName("123"));
        assertTrue(!SqlInspector.isNonSchemaColumnName("email"));
    }
}
