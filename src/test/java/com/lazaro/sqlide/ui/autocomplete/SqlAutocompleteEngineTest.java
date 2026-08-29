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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlAutocompleteEngineTest {

    private SchemaCache cache;
    private SqlAutocompleteEngine engine;

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

        SchemaNode catalog = new SchemaNode("app", NodeType.DATABASE, List.of(users, orders), Map.of());
        cache = new SchemaCache();
        cache.replace(List.of(catalog));
        engine = new SqlAutocompleteEngine(cache);
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
}
