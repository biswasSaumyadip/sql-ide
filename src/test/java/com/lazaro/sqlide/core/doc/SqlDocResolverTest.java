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
    void resolvesProcedureFunctionTriggerAndDelimiterKeywords() {
        String sql = "CREATE PROCEDURE greet() BEGIN SELECT 1; END";
        var procedure = SqlDocResolver.resolve(sql, sql.indexOf("PROCEDURE"), null, null, null);
        assertTrue(procedure.isPresent());
        assertEquals(SqlDocResolver.Kind.KEYWORD, procedure.get().kind());
        assertTrue(procedure.get().code().toLowerCase().contains("stored procedure"));

        var function = SqlDocResolver.resolve("CREATE FUNCTION fn()", 7, null, null, null);
        assertTrue(function.isPresent());
        assertTrue(function.get().code().toLowerCase().contains("stored function"));

        var trigger = SqlDocResolver.resolve("CREATE TRIGGER t", 7, null, null, null);
        assertTrue(trigger.isPresent());
        assertTrue(trigger.get().code().toLowerCase().contains("trigger"));

        String delimiter = "DELIMITER $$";
        var delim = SqlDocResolver.resolve(delimiter, 0, null, null, null);
        assertTrue(delim.isPresent());
        assertTrue(delim.get().code().contains("not sent to the server"));
    }

    @Test
    void resolvesStoredProcedureName() {
        SchemaNode greet = SchemaNode.of("greet_user", SchemaNode.NodeType.PROCEDURE, Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_ROUTINE_KIND, SchemaNode.ROUTINE_PROCEDURE,
                SchemaNode.META_DDL, """
                        CREATE PROCEDURE InsertDummyData()
                        BEGIN
                          DECLARE i INT DEFAULT 1;
                          WHILE i <= 1050 DO
                            INSERT INTO pagination_test (name, email, status)
                            VALUES (
                                  CONCAT('User ', i),
                                  CONCAT('user', i, '@example.com'),
                                  IF(i % 2 = 0, 'Active', 'Pending')
                                 );
                            SET i = i + 1;
                           END WHILE;
                        END
                        """.strip()));
        SchemaCache cache = new SchemaCache();
        cache.replace(List.of(new SchemaNode("app", SchemaNode.NodeType.DATABASE, List.of(greet), Map.of())));

        String sql = "CALL greet_user()";
        var doc = SqlDocResolver.resolve(sql, sql.indexOf("greet_user"), cache, "app", "Local");
        assertTrue(doc.isPresent());
        assertEquals(SqlDocResolver.Kind.PROCEDURE, doc.get().kind());
        assertTrue(doc.get().code().contains("CREATE PROCEDURE InsertDummyData()"));
        assertTrue(doc.get().code().contains("WHILE i <= 1050 DO"));
        assertTrue(doc.get().code().contains("END WHILE"));
    }

    @Test
    void extractsQualifiedIdentifier() {
        var ident = SqlDocResolver.identifierAt("SELECT u.name FROM users u", 10);
        assertEquals("u", ident.qualifier());
        assertEquals("name", ident.name());
    }
}
