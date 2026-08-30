package com.lazaro.sqlide.core.inspection;

import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SqlValueTypesTest {

    @Test
    void flagsNumberIntoVarchar() {
        String message = SqlValueTypes.mismatchMessage("name", "VARCHAR(64)", new LongValue(2));
        assertNotNull(message);
        assertEquals("Type mismatch: column 'name' is VARCHAR, got number", message);
    }

    @Test
    void allowsStringIntoVarchar() {
        assertNull(SqlValueTypes.mismatchMessage("name", "VARCHAR(64)", new StringValue("Human")));
    }

    @Test
    void flagsStringIntoInt() {
        String message = SqlValueTypes.mismatchMessage("id", "INT", new StringValue("x"));
        assertNotNull(message);
        assertEquals("Type mismatch: column 'id' is INT, got string", message);
    }

    @Test
    void allowsNullAnywhere() {
        assertNull(SqlValueTypes.mismatchMessage("name", "VARCHAR", new net.sf.jsqlparser.expression.NullValue()));
    }
}
