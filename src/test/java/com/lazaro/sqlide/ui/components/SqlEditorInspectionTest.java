package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.inspection.InspectionIssue;
import com.lazaro.sqlide.core.inspection.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlEditorInspectionTest {

    @Test
    void delimiterScriptDoesNotFlagFalseSyntaxErrors() {
        SchemaNode id = SchemaNode.of("id", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "INT"));
        SchemaNode email = SchemaNode.of("email", NodeType.COLUMN, Map.of(SchemaNode.META_DATA_TYPE, "VARCHAR"));
        SchemaNode users = new SchemaNode("users", NodeType.TABLE, List.of(id, email), Map.of(
                SchemaNode.META_CATALOG, "app"));
        SchemaCache cache = new SchemaCache();
        cache.replace(List.of(new SchemaNode("app", NodeType.DATABASE, List.of(users), Map.of())));

        String sql = """
                DELIMITER $$
                CREATE PROCEDURE greet()
                BEGIN
                    SELECT 1;
                END$$
                DELIMITER ;
                SELECT email FROM users;
                """;
        List<InspectionIssue> issues = SqlEditorPane.inspectExecutableStatements(sql, cache, "app");
        assertTrue(issues.stream().noneMatch(i -> i.severity() == Severity.ERROR),
                () -> "unexpected errors: " + issues);
    }
}
