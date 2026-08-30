package com.lazaro.sqlide.core.doc;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDocResolverTest {

    @Test
    void resolvesTableAndColumn() {
        SchemaNode id = SchemaNode.of("id", SchemaNode.NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "INT",
                SchemaNode.META_NULLABLE, "false",
                SchemaNode.META_PRIMARY_KEY, "true"));
        SchemaNode name = SchemaNode.of("name", SchemaNode.NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, "VARCHAR(50)",
                SchemaNode.META_NULLABLE, "true"));
        SchemaNode users = new SchemaNode("users", SchemaNode.NodeType.TABLE, List.of(id, name), Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_DDL, "CREATE TABLE users (\n  id INT NOT NULL,\n  name VARCHAR(50)\n);\n"));
        SchemaNode catalog = new SchemaNode("app", SchemaNode.NodeType.DATABASE, List.of(users), Map.of());

        SchemaCache cache = new SchemaCache();
        cache.replace(List.of(catalog));

        String sql = "SELECT name FROM users WHERE id = 1";
        int usersIdx = sql.indexOf("users");
        var tableDoc = SqlDocResolver.resolve(sql, usersIdx, cache, "app", "Local");
        assertTrue(tableDoc.isPresent());
        assertEquals(SqlDocResolver.Kind.TABLE, tableDoc.get().kind());
        assertTrue(tableDoc.get().code().contains("CREATE TABLE users"));

        int nameIdx = sql.indexOf("name");
        var colDoc = SqlDocResolver.resolve(sql, nameIdx, cache, "app", "Local");
        assertTrue(colDoc.isPresent());
        assertEquals(SqlDocResolver.Kind.COLUMN, colDoc.get().kind());
        assertTrue(colDoc.get().code().startsWith("ALTER TABLE users ADD name"));
    }

    @Test
    void extractsQualifiedIdentifier() {
        var ident = SqlDocResolver.identifierAt("SELECT u.name FROM users u", 10);
        assertEquals("u", ident.qualifier());
        assertEquals("name", ident.name());
    }
}
