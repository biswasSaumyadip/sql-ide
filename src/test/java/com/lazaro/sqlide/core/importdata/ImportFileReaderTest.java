package com.lazaro.sqlide.core.importdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportFileReaderTest {

    @TempDir
    Path temp;

    @Test
    void readsCsvWithHeader() throws Exception {
        Path file = temp.resolve("sample.csv");
        Files.writeString(file, "id,name\n1,Ada\n2,\"Lovelace, Countess\"\n", StandardCharsets.UTF_8);

        ImportPreview preview = ImportFileReader.readPreview(file, ImportFormat.AUTO, true);

        assertEquals(ImportFormat.CSV, preview.format());
        assertEquals(List.of("id", "name"), preview.columnNames());
        assertEquals(2, preview.rows().size());
        assertEquals("Lovelace, Countess", preview.rows().get(1).get(1));
    }

    @Test
    void readsTsvWithoutHeader() throws Exception {
        Path file = temp.resolve("sample.tsv");
        Files.writeString(file, "a\tb\nc\td\n", StandardCharsets.UTF_8);

        ImportPreview preview = ImportFileReader.readPreview(file, ImportFormat.TSV, false);

        assertEquals(List.of("column_1", "column_2"), preview.columnNames());
        assertEquals(2, preview.rows().size());
        assertEquals("a", preview.rows().getFirst().getFirst());
    }

    @Test
    void readsJsonArrayOfObjects() throws Exception {
        Path file = temp.resolve("sample.json");
        Files.writeString(file, "[{\"id\":1,\"name\":\"Ada\"},{\"id\":2,\"name\":\"Grace\"}]", StandardCharsets.UTF_8);

        ImportPreview preview = ImportFileReader.readPreview(file, ImportFormat.JSON, true);

        assertTrue(preview.columnNames().contains("id"));
        assertTrue(preview.columnNames().contains("name"));
        assertEquals(2, preview.rows().size());
    }

    @Test
    void parseDelimitedRespectsQuotes() {
        List<String> cells = ImportFileReader.parseDelimitedLine("a,\"b,c\",d", ',');
        assertEquals(List.of("a", "b,c", "d"), cells);
    }
}
