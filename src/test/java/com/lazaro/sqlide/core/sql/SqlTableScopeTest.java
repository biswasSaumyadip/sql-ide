package com.lazaro.sqlide.core.sql;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTableScopeTest {

    @Test
    void resolvesCatalogQualifiedTableAndAliasViaAst() {
        SchemaCache cache = sampleCache();
        String sql = "SELECT * FROM app.users u WHERE u.";
        Map<String, String> aliases = SqlTableScope.resolveAliases(sql, cache, "app");
        assertEquals("users", aliases.get("u"));
        assertEquals("users", aliases.get("users"));
        assertEquals("users", aliases.get("app.users"));
    }

    @Test
    void resolvesMultipleJoinsViaAst() {
        SchemaCache cache = sampleCache();
        String sql = "SELECT * FROM users u INNER JOIN orders o ON o.user_id = u.id WHERE o.";
        Map<String, String> aliases = SqlTableScope.resolveAliases(sql, cache, "app");
        assertEquals("users", aliases.get("u"));
        assertEquals("orders", aliases.get("o"));
    }

    @Test
    void incompleteJoinFallsBackToRegexForKnownTables() {
        SchemaCache cache = sampleCache();
        String sql = "SELECT * FROM users u JOIN ";
        Map<String, String> aliases = SqlTableScope.resolveAliases(sql, cache, "app");
        assertEquals("users", aliases.get("u"));
    }

    @Test
    void resolvesCteName() {
        SchemaCache cache = sampleCache();
        String sql = "WITH active AS (SELECT id FROM users) SELECT * FROM active a WHERE a.";
        Map<String, String> aliases = SqlTableScope.resolveAliases(sql, cache, "app");
        assertTrue(aliases.containsKey("active") || aliases.containsKey("a"),
                "expected CTE alias in " + aliases);
        assertEquals("active", aliases.getOrDefault("a", aliases.get("active")));
    }

    @Test
    void projectsCteSelectListColumns() {
        SchemaCache cache = sampleCache();
        String sql = "WITH active AS (SELECT id, email AS mail FROM users) SELECT * FROM active a WHERE a.";
        SqlTableScope.ResolvedScope scope = SqlTableScope.resolve(sql, cache, "app");
        assertTrue(scope.cteNames().contains("active"));
        List<String> cols = scope.columnsOf("a");
        assertTrue(cols.stream().anyMatch(c -> c.equalsIgnoreCase("id")), "cols=" + cols);
        assertTrue(cols.stream().anyMatch(c -> c.equalsIgnoreCase("mail")), "cols=" + cols);
    }

    @Test
    void projectsSubqueryAliasColumns() {
        SchemaCache cache = sampleCache();
        String sql = "SELECT * FROM (SELECT id FROM users) sub WHERE sub.";
        SqlTableScope.ResolvedScope scope = SqlTableScope.resolve(sql, cache, "app");
        List<String> cols = scope.columnsOf("sub");
        assertTrue(cols.stream().anyMatch(c -> c.equalsIgnoreCase("id")), "cols=" + cols);
    }

    @Test
    void stripTrailingIncompleteRemovesDanglingQualifier() {
        assertTrue(SqlTableScope.stripTrailingIncomplete("SELECT * FROM users u WHERE u.")
                .toLowerCase()
                .contains("from users"));
    }

    private static SchemaCache sampleCache() {
        SchemaNode id = SchemaNode.of("id", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode email = SchemaNode.of("email", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "VARCHAR"));
        SchemaNode users = new SchemaNode("users", NodeType.TABLE, List.of(id, email), Map.of(
                SchemaNode.META_CATALOG, "app"));
        SchemaNode userId = SchemaNode.of("user_id", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode orders = new SchemaNode("orders", NodeType.TABLE, List.of(userId), Map.of(
                SchemaNode.META_CATALOG, "app"));
        SchemaNode app = new SchemaNode("app", NodeType.DATABASE, List.of(users, orders), Map.of());
        SchemaCache cache = new SchemaCache();
        cache.replace(List.of(app));
        return cache;
    }
}
