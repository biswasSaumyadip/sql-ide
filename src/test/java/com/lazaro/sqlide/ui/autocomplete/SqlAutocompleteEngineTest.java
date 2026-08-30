package com.lazaro.sqlide.ui.autocomplete;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Kind;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Suggestion;
import com.lazaro.sqlide.ui.autocomplete.SqlCompletionHygiene;
import com.lazaro.sqlide.ui.autocomplete.SqlSnippetCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlAutocompleteEngineTest {

    private SchemaCache cache;
    private SqlAutocompleteEngine engine;
    private final AtomicReference<String> activeCatalog = new AtomicReference<>("app");
    private final AtomicReference<ConnectionConfig.Driver> dialect =
            new AtomicReference<>(ConnectionConfig.Driver.MYSQL);

    @BeforeEach
    void seed() {
        SchemaNode id = SchemaNode.of("id", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "INT",
                SchemaNode.META_PRIMARY_KEY, "true"));
        SchemaNode email = SchemaNode.of("email", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "VARCHAR(255)"));
        SchemaNode users = new SchemaNode("users", NodeType.TABLE, List.of(id, email), Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_INDEXES, SchemaMetadataCodec.encodeIndexes(List.of(
                        new SchemaMetadataCodec.IndexInfo("idx_users_email", true, List.of("email"))))));

        SchemaNode userId = SchemaNode.of("user_id", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode total = SchemaNode.of("total", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "DECIMAL(10,2)"));
        String fks = SchemaMetadataCodec.encodeForeignKeys(List.of(
                new SchemaMetadataCodec.ForeignKey("fk_orders_user", "user_id", "users", "id")));
        SchemaNode orders = new SchemaNode("orders", NodeType.TABLE, List.of(userId, total), Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_FOREIGN_KEYS, fks));

        SchemaNode salesId = SchemaNode.of("id", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode salesOrders = new SchemaNode("sales_orders", NodeType.TABLE, List.of(salesId), Map.of(
                SchemaNode.META_CATALOG, "app"));

        SchemaNode activeFlag = SchemaNode.of("active", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "BOOLEAN"));
        SchemaNode userView = new SchemaNode("v_active_users", NodeType.VIEW, List.of(activeFlag), Map.of(
                SchemaNode.META_CATALOG, "app"));

        SchemaNode systemCol = SchemaNode.of("COLUMN_NAME", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "VARCHAR"));
        SchemaNode systemTable = new SchemaNode(
                "schema_auto_increment_columns", NodeType.TABLE, List.of(systemCol), Map.of(
                SchemaNode.META_CATALOG, "app"));

        SchemaNode greet = SchemaNode.of("greet_user", NodeType.PROCEDURE, Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_ROUTINE_KIND, SchemaNode.ROUTINE_PROCEDURE));
        SchemaNode addGold = SchemaNode.of("add_gold", NodeType.PROCEDURE, Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_ROUTINE_KIND, SchemaNode.ROUTINE_FUNCTION));

        SchemaNode otherId = SchemaNode.of("sku", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "VARCHAR"));
        SchemaNode products = new SchemaNode("products", NodeType.TABLE, List.of(otherId), Map.of(
                SchemaNode.META_CATALOG, "shop"));
        SchemaNode shopProc = SchemaNode.of("shop_total", NodeType.PROCEDURE, Map.of(
                SchemaNode.META_CATALOG, "shop",
                SchemaNode.META_ROUTINE_KIND, SchemaNode.ROUTINE_PROCEDURE));

        SchemaNode app = new SchemaNode("app", NodeType.DATABASE,
                List.of(users, orders, salesOrders, userView, systemTable, greet, addGold), Map.of());
        SchemaNode shop = new SchemaNode("shop", NodeType.DATABASE, List.of(products, shopProc), Map.of());
        cache = new SchemaCache();
        cache.replace(List.of(app, shop));
        engine = new SqlAutocompleteEngine(cache, activeCatalog::get, dialect::get);
    }

    private List<Suggestion> suggest(String sql) {
        return engine.suggest(sql, sql.length()).items();
    }

    private List<Suggestion> suggest(String sql, boolean invoked) {
        return engine.suggest(sql, sql.length(), invoked).items();
    }

    @Test
    @DisplayName("typing after FROM suggests tables, not a wall of keywords")
    void suggestsTablesAfterFrom() {
        String sql = "SELECT * FROM ";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.TABLE && s.insertText().equals("users")));
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.TABLE && s.insertText().equals("orders")));
        assertTrue(suggestions.stream().noneMatch(s -> s.insertText().equals("SELECT")),
                "FROM context must not dump unrelated keywords");
        assertTrue(suggestions.getFirst().kind() == Kind.TABLE
                || suggestions.getFirst().kind() == Kind.JOIN
                || suggestions.getFirst().kind() == Kind.VIEW
                || suggestions.getFirst().kind() == Kind.SCHEMA);
        assertTrue(suggestions.stream().noneMatch(s -> s.kind() == Kind.PROCEDURE),
                "FROM must not list stored procedures");
    }

    @Test
    @DisplayName("typing after CALL suggests procedures from the active catalog")
    void suggestsProceduresAfterCall() {
        String sql = "CALL ";
        assertTrue(engine.shouldAutoPopup(sql, sql.length()));
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s ->
                s.kind() == Kind.PROCEDURE && s.name().equals("greet_user")), suggestions.toString());
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("greet_user()")));
        assertTrue(suggestions.stream().noneMatch(s -> s.name().equals("users")),
                "CALL must not dump tables");
        assertTrue(suggestions.stream().noneMatch(s -> s.name().equals("add_gold")),
                "stored functions must not appear after CALL");
        assertTrue(suggestions.stream().noneMatch(s -> s.name().equals("shop_total")),
                "procedures from other catalogs stay hidden");
    }

    @Test
    @DisplayName("CALL suggestions follow the active database")
    void suggestsProceduresFromActiveCatalog() {
        activeCatalog.set("shop");
        List<Suggestion> suggestions = suggest("CALL ");
        assertTrue(suggestions.stream().anyMatch(s -> s.name().equals("shop_total")));
        assertTrue(suggestions.stream().noneMatch(s -> s.name().equals("greet_user")));
    }

    @Test
    @DisplayName("PROCEDURE / FUNCTION / TRIGGER / DELIMITER completions include docs")
    void routineKeywordsHaveDocumentation() {
        List<Suggestion> suggestions = suggest("PROCE", true);
        assertTrue(suggestions.stream().anyMatch(s ->
                s.name().equals("PROCEDURE") && s.documentation().toLowerCase().contains("stored procedure")),
                suggestions.toString());
        assertTrue(suggest("FUNCT", true).stream().anyMatch(s ->
                s.name().equals("FUNCTION") && s.documentation().toLowerCase().contains("stored function")));
        assertTrue(suggest("TRIGG", true).stream().anyMatch(s ->
                s.name().equals("TRIGGER") && s.documentation().toLowerCase().contains("trigger")));
        assertTrue(suggest("DELIM", true).stream().anyMatch(s ->
                s.name().equals("DELIMITER") && s.documentation().toLowerCase().contains("not sent")));
    }

    @Test
    @DisplayName("table suggestions are scoped to the active database")
    void suggestsOnlyTablesFromActiveCatalog() {
        String sql = "SELECT * FROM ";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("users")));
        assertTrue(suggestions.stream().noneMatch(s -> s.insertText().equals("products")),
                "shop.products must not appear while app is selected");

        activeCatalog.set("shop");
        List<Suggestion> shopSuggestions = suggest(sql);
        assertTrue(shopSuggestions.stream().anyMatch(s -> s.insertText().equals("products")));
        assertTrue(shopSuggestions.stream().noneMatch(s -> s.insertText().equals("users")));
    }

    @Test
    @DisplayName("system-like tables rank below ordinary tables")
    void demotesSystemTables() {
        String sql = "SELECT * FROM ";
        List<Suggestion> suggestions = suggest(sql);
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
        List<Suggestion> suggestions = suggest(sql);
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
    @DisplayName("INSERT column list demotes already-typed columns after a comma")
    void insertColumnListDeprioritizesUsed() {
        String sql = "INSERT INTO users (id, ";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().allMatch(s -> s.kind() == Kind.COLUMN));
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("email")));
        int emailAt = indexOf(suggestions, "email");
        int idAt = indexOf(suggestions, "id");
        assertTrue(emailAt >= 0 && idAt >= 0 && emailAt < idAt,
                "already-listed id should rank below email: " + suggestions);
    }

    @Test
    @DisplayName("alias. suggests columns of that table")
    void suggestsColumnsAfterAliasDot() {
        String sql = "SELECT * FROM users u WHERE u.";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.COLUMN && s.insertText().equals("email")));
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.COLUMN && s.insertText().equals("id")));
        assertEquals(Kind.COLUMN, suggestions.getFirst().kind());
    }

    @Test
    @DisplayName("JOIN after a known table suggests FK-derived ON clauses first")
    void suggestsJoinFromForeignKeys() {
        String sql = "SELECT * FROM users u JOIN ";
        List<Suggestion> suggestions = suggest(sql);
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
        assertTrue(suggest(sql).isEmpty());
    }

    @Test
    @DisplayName("prefix of 2+ letters suggests SELECT via auto-popup")
    void filtersKeywords() {
        String sql = "SEL";
        assertTrue(engine.shouldAutoPopup(sql, sql.length()));
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("SELECT")));
        assertFalse(suggestions.stream().anyMatch(s -> s.insertText().equals("FROM")));
    }

    @Test
    @DisplayName("Ctrl+Space (invoked) surfaces keywords even with a short prefix")
    void invokedShowsKeywords() {
        String sql = "S";
        List<Suggestion> suggestions = suggest(sql, true);
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("SELECT")));
    }

    @Test
    @DisplayName("completions are suppressed inside string literals")
    void suppressesInsideStrings() {
        String sql = "SELECT 'FROM ";
        assertTrue(suggest(sql).isEmpty());
    }

    @Test
    @DisplayName("matchScore prefers prefix over substring")
    void ranksPrefixHigher() {
        assertTrue(SqlAutocompleteEngine.matchScore("users", "us")
                > SqlAutocompleteEngine.matchScore("status", "us"));
    }

    @Test
    @DisplayName("mid-token match: orders hits sales_orders")
    void midTokenUnderscoreMatch() {
        assertTrue(SqlAutocompleteEngine.matchScore("sales_orders", "orders") > 0);
        String sql = "SELECT * FROM orders";
        // prefix "orders" should still list sales_orders via mid-token
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("sales_orders")),
                "expected sales_orders for mid-token orders: " + suggestions);
    }

    @Test
    @DisplayName("typo tolerance: one edit matches")
    void typoToleranceOneEdit() {
        assertTrue(SqlAutocompleteEngine.editDistanceAtMostOne("users", "uzers"));
        assertTrue(SqlAutocompleteEngine.matchScore("users", "uzers") > 0);
        assertTrue(SqlAutocompleteEngine.editDistanceAtMostOne("users", "userrs"));
        assertFalse(SqlAutocompleteEngine.editDistanceAtMostOne("users", "xyz"));
    }

    @Test
    @DisplayName("transaction keywords are suggested")
    void suggestsTransactionKeywords() {
        assertTrue(suggest("COM").stream().anyMatch(s -> s.insertText().equals("COMMIT")));
        assertTrue(suggest("ROL").stream().anyMatch(s -> s.insertText().equals("ROLLBACK")));
        assertTrue(suggest("STAR").stream()
                .anyMatch(s -> s.insertText().equals("START TRANSACTION")));
        assertTrue(suggest("START TRA").stream()
                .anyMatch(s -> s.insertText().equals("TRANSACTION")));
    }

    @Test
    @DisplayName("catalog.table alias resolves columns via AST scope")
    void suggestsColumnsAfterCatalogQualifiedAlias() {
        String sql = "SELECT * FROM app.users u WHERE u.";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.COLUMN && s.insertText().equals("email")));
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.COLUMN && s.insertText().equals("id")));
    }

    @Test
    @DisplayName("multi-join aliases stay independent")
    void suggestsColumnsForSecondJoinAlias() {
        String sql = "SELECT * FROM users u JOIN orders o ON o.user_id = u.id WHERE o.";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("user_id")));
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("total")));
        assertTrue(suggestions.stream().noneMatch(s -> s.insertText().equals("email")),
                "must not mix users columns into o.: " + suggestions);
    }

    @Test
    @DisplayName("views appear as VIEW kind")
    void suggestsViewsAsViewKind() {
        List<Suggestion> suggestions = suggest("SELECT * FROM v_");
        assertTrue(suggestions.stream().anyMatch(s ->
                s.kind() == Kind.VIEW && s.insertText().equals("v_active_users")), suggestions.toString());
    }

    @Test
    @DisplayName("schemas are suggested after USE / FROM")
    void suggestsSchemas() {
        List<Suggestion> afterUse = suggest("USE ");
        assertTrue(afterUse.stream().anyMatch(s -> s.kind() == Kind.SCHEMA && s.insertText().equals("app")));
        List<Suggestion> afterFrom = suggest("SELECT * FROM ");
        assertTrue(afterFrom.stream().anyMatch(s -> s.kind() == Kind.SCHEMA && s.insertText().equals("app")));
    }

    @Test
    @DisplayName("indexes are suggested after INDEX")
    void suggestsIndexes() {
        List<Suggestion> suggestions = suggest("DROP INDEX ");
        assertTrue(suggestions.stream().anyMatch(s ->
                s.kind() == Kind.INDEX && s.insertText().equals("idx_users_email")), suggestions.toString());
    }

    @Test
    @DisplayName("functions such as COUNT and COALESCE are suggested")
    void suggestsFunctions() {
        List<Suggestion> suggestions = suggest("COU");
        assertTrue(suggestions.stream().anyMatch(s ->
                s.kind() == Kind.FUNCTION && s.name().equals("COUNT")), suggestions.toString());
        List<Suggestion> coalesce = suggest("COAL");
        assertTrue(coalesce.stream().anyMatch(s ->
                s.kind() == Kind.FUNCTION && s.name().equals("COALESCE")), coalesce.toString());
    }

    @Test
    @DisplayName("dialect keywords differ for Postgres vs MySQL")
    void dialectKeywords() {
        dialect.set(ConnectionConfig.Driver.POSTGRESQL);
        engine = new SqlAutocompleteEngine(cache, activeCatalog::get, dialect::get);
        assertTrue(suggest("ILI").stream().anyMatch(s -> s.insertText().equals("ILIKE")));
        assertTrue(suggest("RET").stream().anyMatch(s -> s.insertText().equals("RETURNING")));

        dialect.set(ConnectionConfig.Driver.MYSQL);
        engine = new SqlAutocompleteEngine(cache, activeCatalog::get, dialect::get);
        assertTrue(suggest("REG").stream().anyMatch(s -> s.insertText().equals("REGEXP")));
        assertTrue(suggest("ILI").stream().noneMatch(s -> s.insertText().equals("ILIKE")));
    }

    @Test
    @DisplayName("sel / ins abbreviations expand to snippets with placeholders")
    void snippetCompletions() {
        List<Suggestion> sel = suggest("sel");
        assertTrue(sel.stream().anyMatch(s ->
                s.kind() == Kind.SNIPPET && s.insertText().contains("$table$")), sel.toString());
        List<Suggestion> ins = suggest("ins");
        assertTrue(ins.stream().anyMatch(s ->
                s.kind() == Kind.SNIPPET
                        && s.insertText().contains("INSERT INTO")
                        && s.insertText().contains("$columns$")
                        && s.insertText().contains("$values$")), ins.toString());
    }

    @Test
    @DisplayName("snippet apply expands $name$ markers into selectable ranges")
    void snippetPlaceholderExpansion() {
        var applied = SqlSnippetCatalog.apply("INSERT INTO $table$ ($columns$) VALUES ($values$)");
        assertEquals("INSERT INTO table (columns) VALUES (values)", applied.text());
        assertEquals(3, applied.ranges().size());
        assertEquals("table", applied.text().substring(applied.ranges().get(0)[0], applied.ranges().get(0)[1]));
    }

    @Test
    @DisplayName("after : suggests run-config parameter names")
    void suggestsNamedParameters() {
        AtomicReference<Map<String, String>> params = new AtomicReference<>(Map.of(
                "userId", "1",
                "status", "open"));
        engine = new SqlAutocompleteEngine(
                cache, activeCatalog::get, dialect::get, params::get,
                SqlCompletionHygiene.Style.defaults());
        List<Suggestion> suggestions = suggest("SELECT * FROM users WHERE id = :u");
        assertTrue(suggestions.stream().anyMatch(s ->
                s.kind() == Kind.PARAMETER && s.name().equals("userId")), suggestions.toString());
    }

    @Test
    @DisplayName("after ? offers :name replacements from run config")
    void suggestsNamedParamsForQuestionMark() {
        AtomicReference<Map<String, String>> params = new AtomicReference<>(Map.of("userId", "1"));
        engine = new SqlAutocompleteEngine(
                cache, activeCatalog::get, dialect::get, params::get,
                SqlCompletionHygiene.Style.defaults());
        List<Suggestion> suggestions = suggest("SELECT * FROM users WHERE id = ?");
        assertTrue(suggestions.stream().anyMatch(s ->
                s.kind() == Kind.PARAMETER && s.insertText().equals(":userId")), suggestions.toString());
    }

    @Test
    @DisplayName("CTE columns are suggested after alias dot")
    void suggestsCteColumns() {
        String sql = "WITH active AS (SELECT id, email FROM users) SELECT * FROM active a WHERE a.";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("id")), suggestions.toString());
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("email")), suggestions.toString());
    }

    @Test
    @DisplayName("CTE names appear like tables after FROM")
    void suggestsCteNamesAfterFrom() {
        String sql = "WITH active AS (SELECT id FROM users) SELECT * FROM ";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s ->
                s.insertText().equals("active") && "cte".equals(s.detail())), suggestions.toString());
    }

    @Test
    @DisplayName("auto-quotes reserved identifiers on insert")
    void quotesReservedIdentifiers() {
        SchemaNode orderCol = SchemaNode.of("order", NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode reserved = new SchemaNode("order", NodeType.TABLE, List.of(orderCol), Map.of(
                SchemaNode.META_CATALOG, "app"));
        SchemaNode app = new SchemaNode("app", NodeType.DATABASE, List.of(reserved), Map.of());
        SchemaCache reservedCache = new SchemaCache();
        reservedCache.replace(List.of(app));
        engine = new SqlAutocompleteEngine(
                reservedCache, () -> "app", dialect::get, Map::of,
                new SqlCompletionHygiene.Style(false, true, true));
        List<Suggestion> suggestions = suggest("SELECT * FROM ");
        assertTrue(suggestions.stream().anyMatch(s ->
                s.name().equals("order") && s.insertText().contains("`")), suggestions.toString());
    }

    @Test
    @DisplayName("lowerKeywords style lowercases keyword inserts")
    void lowerKeywordsStyle() {
        engine = new SqlAutocompleteEngine(
                cache, activeCatalog::get, dialect::get, Map::of,
                new SqlCompletionHygiene.Style(true, true, true));
        List<Suggestion> suggestions = suggest("SEL");
        assertTrue(suggestions.stream().anyMatch(s ->
                s.kind() == Kind.KEYWORD && s.insertText().equals("select")), suggestions.toString());
    }

    @Test
    @DisplayName("INSERT INTO known table ( offers all columns comma-separated")
    void insertAllColumnsCsvForKnownTable() {
        String sql = "INSERT INTO users (";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().anyMatch(s ->
                s.kind() == Kind.COLUMN
                        && s.insertText().contains("id")
                        && s.insertText().contains("email")
                        && s.insertText().contains(",")),
                "expected CSV of all columns: " + suggestions);
        assertEquals(Kind.COLUMN, suggestions.getFirst().kind());
        assertTrue(suggestions.getFirst().insertText().contains(","));
    }

    @Test
    @DisplayName("INSERT INTO unknown table ( does not invent an all-columns CSV")
    void insertAllColumnsRequiresResolvedTable() {
        String sql = "INSERT INTO no_such_table (";
        List<Suggestion> suggestions = suggest(sql);
        assertTrue(suggestions.stream().noneMatch(s ->
                s.insertText().contains(",") && s.kind() == Kind.COLUMN),
                "must not expand columns for unknown table: " + suggestions);
    }

    @Test
    @DisplayName("INSERT all-columns CSV skips already-listed columns")
    void insertAllColumnsSkipsUsed() {
        String sql = "INSERT INTO users (id, ";
        List<Suggestion> suggestions = suggest(sql);
        Optional<Suggestion> expand = suggestions.stream()
                .filter(s -> "remaining columns".equals(s.detail()) || "all columns".equals(s.detail()))
                .findFirst();
        assertTrue(expand.isPresent(), "expected remaining-columns suggestion: " + suggestions);
        assertFalse(expand.get().insertText().toLowerCase().startsWith("id"),
                "should not re-list id: " + expand.get().insertText());
        assertTrue(expand.get().insertText().contains("email"));
    }

    @Test
    @DisplayName("SELECT * expands to column CSV when FROM table is known")
    void selectStarExpandsToColumnCsv() {
        String sql = "SELECT * FROM users";
        int caret = sql.indexOf('*') + 1;
        List<Suggestion> suggestions = engine.suggest(sql, caret).items();
        assertTrue(suggestions.stream().anyMatch(s ->
                s.kind() == Kind.COLUMN
                        && s.insertText().contains("id")
                        && s.insertText().contains("email")
                        && s.insertText().contains(",")),
                "expected expand-* CSV: " + suggestions);
    }

    @Test
    @DisplayName("SELECT * does not expand columns without a known FROM table")
    void selectStarWithoutKnownTableKeepsStarOnly() {
        String sql = "SELECT * FROM no_such_table";
        int caret = sql.indexOf('*') + 1;
        List<Suggestion> suggestions = engine.suggest(sql, caret).items();
        assertTrue(suggestions.stream().anyMatch(s -> s.insertText().equals("*")));
        assertTrue(suggestions.stream().noneMatch(s ->
                s.kind() == Kind.COLUMN && s.insertText().contains(",")),
                "must not expand for unknown FROM table: " + suggestions);
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
