package com.lazaro.sqlide.ui.autocomplete;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Kind;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Suggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlAutocompleteEngineTest {

    private SchemaCache cache;
    private SqlAutocompleteEngine engine;
    private final AtomicReference<String> activeCatalog = new AtomicReference<>("app");

    @BeforeEach
    void seed() {
        SchemaNode id = SchemaNode.of("id", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "INT",
                SchemaNode.META_PRIMARY_KEY, "true"));
        SchemaNode email = SchemaNode.of("email", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "VARCHAR(255)"));
        SchemaNode users = new SchemaNode("users", NodeType.TABLE, List.of(id, email), Map.of(
                SchemaNode.META_CATALOG, "app"));

        SchemaNode userId = SchemaNode.of("user_id", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode total = SchemaNode.of("total", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "DECIMAL(10,2)"));
        String fks = SchemaMetadataCodec.encodeForeignKeys(List.of(
                new SchemaMetadataCodec.ForeignKey("fk_orders_user", "user_id", "users", "id")));
        SchemaNode orders = new SchemaNode("orders", NodeType.TABLE, List.of(userId, total), Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_FOREIGN_KEYS, fks));

        SchemaNode systemCol = SchemaNode.of("COLUMN_NAME", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "VARCHAR"));
        SchemaNode systemTable = new SchemaNode(
                "schema_auto_increment_columns", NodeType.TABLE, List.of(systemCol), Map.of(
                SchemaNode.META_CATALOG, "app"));

        SchemaNode otherId = SchemaNode.of("sku", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "VARCHAR"));
        SchemaNode products = new SchemaNode("products", NodeType.TABLE, List.of(otherId), Map.of(
                SchemaNode.META_CATALOG, "shop"));

        SchemaNode app = new SchemaNode("app", NodeType.DATABASE, List.of(users, orders, systemTable), Map.of());
        SchemaNode shop = new SchemaNode("shop", NodeType.DATABASE, List.of(products), Map.of());
        cache = new SchemaCache();
        cache.replace(List.of(app, shop));
        engine = new SqlAutocompleteEngine(cache, activeCatalog::get);
    }

    @Test
    @DisplayName("typing after FROM suggests tables, not a wall of keywords")
    void suggestsTablesAfterFrom() {
        String sql = "SELECT * FROM ";
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.TABLE && s.insertText().equals("users")));
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.TABLE && s.insertText().equals("orders")));
        assertTrue(suggestions.stream().noneMatch(s -> s.insertText().equals("SELECT")),
                "FROM context must not dump unrelated keywords");
        assertTrue(suggestions.getFirst().kind() == Kind.TABLE || suggestions.getFirst().kind() == Kind.JOIN);
    }

    @Test
    @DisplayName("table suggestions are scoped to the active database")
    void suggestsOnlyTablesFromActiveCatalog() {
        String sql = "SELECT * FROM ";
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("users")));
        assertTrue(suggestions.stream().noneMatch(s -> s.insertText().equals("products")),
                "shop.products must not appear while app is selected");

        activeCatalog.set("shop");
        List<Suggestion> shopSuggestions = engine.suggest(sql, sql.length());
        assertTrue(shopSuggestions.stream().anyMatch(s -> s.insertText().equals("products")));
        assertTrue(shopSuggestions.stream().noneMatch(s -> s.insertText().equals("users")));
    }

    @Test
    @DisplayName("system-like tables rank below ordinary tables")
    void demotesSystemTables() {
        String sql = "SELECT * FROM ";
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("schema_auto_increment_columns")));

        int usersAt = indexOf(suggestions, "users");
        int systemAt = indexOf(suggestions, "schema_auto_increment_columns");
        assertTrue(usersAt >= 0 && systemAt >= 0 && usersAt < systemAt,
                "users should rank above schema_auto_increment_columns: " + suggestions);
    }

    @Test
    @DisplayName("INSERT INTO t ( suggests only that table's columns")
    void insertColumnListSuggestsOnlyColumns() {
        String sql = "INSERT INTO users (";
        assertTrue(engine.shouldAutoPopup(sql, sql.length()));
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertFalse(suggestions.isEmpty());
        assertTrue(suggestions.stream().allMatch(s -> s.kind() == Kind.COLUMN),
                "INSERT column list must not mix tables/keywords: " + suggestions);
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("id")));
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("email")));
        assertTrue(suggestions.stream().noneMatch(s -> s.insertText().equals("users")));
        assertTrue(suggestions.stream().noneMatch(s -> s.insertText().equals("user_id")),
                "must not leak columns from other tables");
    }

    @Test
    @DisplayName("INSERT column list keeps filtering after a comma")
    void insertColumnListAfterComma() {
        String sql = "INSERT INTO users (id, ";
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertTrue(suggestions.stream().allMatch(s -> s.kind() == Kind.COLUMN));
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("email")));
    }

    @Test
    @DisplayName("alias. suggests columns of that table")
    void suggestsColumnsAfterAliasDot() {
        String sql = "SELECT * FROM users u WHERE u.";
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.COLUMN && s.insertText().equals("email")));
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.COLUMN && s.insertText().equals("id")));
        assertEquals(Kind.COLUMN, suggestions.getFirst().kind());
    }

    @Test
    @DisplayName("JOIN after a known table suggests FK-derived ON clauses first")
    void suggestsJoinFromForeignKeys() {
        String sql = "SELECT * FROM users u JOIN ";
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.JOIN
                && s.insertText().contains("orders")
                && s.insertText().contains("user_id")
                && s.insertText().contains("users")), "expected JOIN snippet in " + suggestions);
        assertEquals(Kind.JOIN, suggestions.getFirst().kind(), "FK joins should rank above plain tables");
    }

    @Test
    @DisplayName("short free typing does not auto-spam keywords")
    void doesNotPopupOnSingleLetter() {
        String sql = "S";
        assertFalse(engine.shouldAutoPopup(sql, sql.length()));
        assertTrue(engine.suggest(sql, sql.length()).isEmpty());
    }

    @Test
    @DisplayName("prefix of 2+ letters suggests SELECT via auto-popup")
    void filtersKeywords() {
        String sql = "SEL";
        assertTrue(engine.shouldAutoPopup(sql, sql.length()));
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("SELECT")));
        assertFalse(suggestions.stream().anyMatch(s -> s.insertText().equals("FROM")));
    }

    @Test
    @DisplayName("Ctrl+Space (invoked) surfaces keywords even with a short prefix")
    void invokedShowsKeywords() {
        String sql = "S";
        List<Suggestion> suggestions = engine.suggest(sql, sql.length(), true);
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("SELECT")));
    }

    @Test
    @DisplayName("completions are suppressed inside string literals")
    void suppressesInsideStrings() {
        String sql = "SELECT 'FROM ";
        assertTrue(engine.suggest(sql, sql.length()).isEmpty());
    }

    @Test
    @DisplayName("matchScore prefers prefix over substring")
    void ranksPrefixHigher() {
        assertTrue(SqlAutocompleteEngine.matchScore("users", "us")
                > SqlAutocompleteEngine.matchScore("status", "us"));
    }

    @Test
    @DisplayName("transaction keywords are suggested")
    void suggestsTransactionKeywords() {
        assertTrue(engine.suggest("COM", 3).stream().anyMatch(s -> s.insertText().equals("COMMIT")));
        assertTrue(engine.suggest("ROL", 3).stream().anyMatch(s -> s.insertText().equals("ROLLBACK")));
        assertTrue(engine.suggest("STAR", 4).stream()
                .anyMatch(s -> s.insertText().equals("START TRANSACTION")));
        assertTrue(engine.suggest("START TRA", "START TRA".length()).stream()
                .anyMatch(s -> s.insertText().equals("TRANSACTION")));
    }

    @Test
    @DisplayName("catalog.table alias resolves columns via AST scope")
    void suggestsColumnsAfterCatalogQualifiedAlias() {
        String sql = "SELECT * FROM app.users u WHERE u.";
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.COLUMN && s.insertText().equals("email")));
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.COLUMN && s.insertText().equals("id")));
    }

    @Test
    @DisplayName("multi-join aliases stay independent")
    void suggestsColumnsForSecondJoinAlias() {
        String sql = "SELECT * FROM users u JOIN orders o ON o.user_id = u.id WHERE o.";
        List<Suggestion> suggestions = engine.suggest(sql, sql.length());
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("user_id")));
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("total")));
        assertTrue(suggestions.stream().noneMatch(s -> s.insertText().equals("email")),
                "must not mix users columns into o.: " + suggestions);
    }

    private static int indexOf(List<Suggestion> suggestions, String insertText) {
        for (int i = 0; i < suggestions.size(); i++) {
            if (suggestions.get(i).insertText().equals(insertText)) {
                return i;
            }
        }
        return -1;
    }
}
