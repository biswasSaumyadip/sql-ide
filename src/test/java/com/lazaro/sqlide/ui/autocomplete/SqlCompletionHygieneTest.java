package com.lazaro.sqlide.ui.autocomplete;

import com.lazaro.sqlide.ui.autocomplete.SqlCompletionHygiene.KeywordCasing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlCompletionHygieneTest {

    @Test
    @DisplayName("tableAlias uses the first letter, or initials of snake_case parts")
    void tableAliasCompactForm() {
        assertEquals("u", SqlCompletionHygiene.tableAlias("users"));
        assertEquals("oi", SqlCompletionHygiene.tableAlias("order_items"));
        assertEquals("t", SqlCompletionHygiene.tableAlias(""));
        assertEquals("o", SqlCompletionHygiene.tableAlias("`orders`"));
    }

    @Test
    @DisplayName("keyword casing covers UPPERCASE, lowercase, and Capitalize")
    void applyKeywordCasing() {
        assertEquals("SELECT", SqlCompletionHygiene.applyKeywordCasing("select", KeywordCasing.UPPERCASE));
        assertEquals("select", SqlCompletionHygiene.applyKeywordCasing("SELECT", KeywordCasing.LOWERCASE));
        assertEquals("Select", SqlCompletionHygiene.applyKeywordCasing("SELECT", KeywordCasing.CAPITALIZE));
    }

    @Test
    @DisplayName("KeywordCasing.parse accepts labels and enum names")
    void parseCasing() {
        assertEquals(KeywordCasing.UPPERCASE, KeywordCasing.parse(null));
        assertEquals(KeywordCasing.LOWERCASE, KeywordCasing.parse("lowercase"));
        assertEquals(KeywordCasing.CAPITALIZE, KeywordCasing.parse("Capitalize"));
        assertEquals(KeywordCasing.UPPERCASE, KeywordCasing.parse("UPPERCASE"));
    }
}
