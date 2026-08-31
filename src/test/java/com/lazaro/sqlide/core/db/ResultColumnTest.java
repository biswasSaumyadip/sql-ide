package com.lazaro.sqlide.core.db;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultColumnTest {

    @Test
    void numericTypesUse123Badge() {
        ResultColumn column = new ResultColumn("id", "INT", Types.INTEGER, true, false);
        assertEquals(ResultColumn.Kind.NUMERIC, column.kind());
        assertEquals("123", column.typeBadge());
        assertTrue(column.primaryKey());
        assertTrue(column.typeTooltip().contains("primary key"));
    }

    @Test
    void varcharUsesAaBadge() {
        ResultColumn column = new ResultColumn("name", "VARCHAR", Types.VARCHAR, false, false);
        assertEquals(ResultColumn.Kind.TEXT, column.kind());
        assertEquals("Aa", column.typeBadge());
    }

    @Test
    void timestampIsTemporalWithEmptyTextBadge() {
        ResultColumn column = new ResultColumn("created_at", "TIMESTAMP", Types.TIMESTAMP, false, false);
        assertEquals(ResultColumn.Kind.TEMPORAL, column.kind());
        assertEquals("", column.typeBadge());
    }

    @Test
    void typeNameFallbackWhenSqlTypeIsOther() {
        ResultColumn json = new ResultColumn("payload", "JSON", Types.OTHER, false, false);
        assertEquals(ResultColumn.Kind.TEXT, json.kind());
        ResultColumn blob = new ResultColumn("bin", "LONGBLOB", Types.OTHER, false, false);
        assertEquals(ResultColumn.Kind.BINARY, blob.kind());
        assertEquals("[]", blob.typeBadge());
    }

    @Test
    void fromNamesSynthesizesUnknownTypes() {
        List<ResultColumn> columns = ResultColumn.fromNames(List.of("a", "b"));
        assertEquals(2, columns.size());
        assertEquals("a", columns.getFirst().name());
        assertEquals(ResultColumn.Kind.OTHER, columns.getFirst().kind());
        assertFalse(columns.getFirst().primaryKey());
    }

    @Test
    void withKeysPreservesType() {
        ResultColumn base = new ResultColumn("user_id", "INT", Types.INTEGER, false, false);
        ResultColumn keyed = base.withKeys(false, true);
        assertTrue(keyed.foreignKey());
        assertFalse(keyed.primaryKey());
        assertEquals(Types.INTEGER, keyed.sqlType());
        assertTrue(keyed.typeTooltip().contains("foreign key"));
    }
}
