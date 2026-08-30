package com.lazaro.sqlide.core.export;

import com.lazaro.sqlide.core.db.QueryResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultExporterTest {

    @Test
    void exportsCsvJsonAndInserts() {
        List<String> nullRow = new ArrayList<>(List.of("2", "x"));
        nullRow.set(1, null);
        QueryResult result = QueryResult.ofRows(
                List.of("id", "name"),
                List.of(List.of("1", "Ada"), nullRow),
                3L);

        String csv = ResultExporter.toCsv(result);
        assertTrue(csv.startsWith("id,name"));
        assertTrue(csv.contains("1,Ada"));

        String json = ResultExporter.toJson(result);
        assertTrue(json.contains("\"id\": \"1\""));
        assertTrue(json.contains("null"));

        String inserts = ResultExporter.toInserts(result, "people");
        assertEquals(
                "INSERT INTO people (id, name) VALUES ('1', 'Ada');\n"
                        + "INSERT INTO people (id, name) VALUES ('2', NULL);\n",
                inserts);

        String tsv = ResultExporter.toTsv(result);
        assertTrue(tsv.startsWith("id\tname"));
        assertTrue(tsv.contains("1\tAda"));
        assertTrue(tsv.contains("2\t"));

        QueryResult selection = ResultExporter.subset(result, List.of(List.of("1", "Ada")));
        assertEquals(1, selection.rowCount());
        assertEquals("id,name\n1,Ada\n", ResultExporter.toCsv(selection));
    }
}
