package com.lazaro.sqlide.core.importdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Reads a bounded preview of an import file for the wizard (CSV / TSV / JSON).
 * Excel (.xlsx) is recognised but not parsed yet.
 */
public final class ImportFileReader {

    public static final int DEFAULT_PREVIEW_ROWS = 50;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ImportFileReader() {
    }

    public static ImportPreview readPreview(Path path, ImportFormat format, boolean firstRowIsHeader)
            throws IOException {
        return readPreview(path, format, firstRowIsHeader, DEFAULT_PREVIEW_ROWS);
    }

    public static ImportPreview readPreview(
            Path path, ImportFormat format, boolean firstRowIsHeader, int maxRows) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) {
            throw new IOException("File not found: " + path);
        }
        ImportFormat concrete = ImportFormat.requireConcrete(format, path);
        return switch (concrete) {
            case CSV -> readDelimited(path, concrete, ',', firstRowIsHeader, maxRows);
            case TSV -> readDelimited(path, concrete, '\t', firstRowIsHeader, maxRows);
            case JSON -> readJson(path, maxRows);
            case XLSX -> throw new IOException(
                    "Excel (.xlsx) preview is not supported yet. Export as CSV or TSV and try again.");
            case AUTO -> throw new IOException("Could not detect file format");
        };
    }

    private static ImportPreview readDelimited(
            Path path, ImportFormat format, char separator, boolean firstRowIsHeader, int maxRows)
            throws IOException {
        List<List<String>> raw = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() && raw.isEmpty()) {
                    continue;
                }
                raw.add(parseDelimitedLine(line, separator));
                // Keep one extra when header so we can return maxRows of data.
                if (raw.size() >= maxRows + (firstRowIsHeader ? 1 : 0) + 1) {
                    break;
                }
            }
        }
        if (raw.isEmpty()) {
            return new ImportPreview(path, format, List.of(), List.of(), 0);
        }

        List<String> columns;
        List<List<String>> data;
        if (firstRowIsHeader) {
            columns = normalizeHeaders(raw.getFirst());
            data = new ArrayList<>(raw.subList(1, Math.min(raw.size(), maxRows + 1)));
        } else {
            int width = raw.stream().mapToInt(List::size).max().orElse(0);
            columns = syntheticHeaders(width);
            data = new ArrayList<>(raw.subList(0, Math.min(raw.size(), maxRows)));
            for (List<String> row : data) {
                pad(row, width);
            }
        }
        for (List<String> row : data) {
            pad(row, columns.size());
        }
        long hint = countLines(path);
        if (firstRowIsHeader && hint > 0) {
            hint = Math.max(0, hint - 1);
        }
        return new ImportPreview(path, format, columns, data, hint);
    }

    private static ImportPreview readJson(Path path, int maxRows) throws IOException {
        JsonNode root = JSON.readTree(path.toFile());
        if (root == null || root.isNull()) {
            return new ImportPreview(path, ImportFormat.JSON, List.of(), List.of(), 0);
        }
        if (!root.isArray()) {
            throw new IOException("JSON import expects a top-level array of objects");
        }
        Set<String> keys = new LinkedHashSet<>();
        List<JsonNode> objects = new ArrayList<>();
        Iterator<JsonNode> it = root.elements();
        while (it.hasNext() && objects.size() < maxRows) {
            JsonNode node = it.next();
            if (node != null && node.isObject()) {
                node.fieldNames().forEachRemaining(keys::add);
                objects.add(node);
            }
        }
        List<String> columns = List.copyOf(keys);
        List<List<String>> rows = new ArrayList<>(objects.size());
        for (JsonNode object : objects) {
            List<String> row = new ArrayList<>(columns.size());
            for (String key : columns) {
                JsonNode value = object.get(key);
                if (value == null || value.isNull()) {
                    row.add(null);
                } else if (value.isValueNode()) {
                    row.add(value.asText());
                } else {
                    row.add(value.toString());
                }
            }
            rows.add(row);
        }
        return new ImportPreview(path, ImportFormat.JSON, columns, rows, root.size());
    }

    static List<String> parseDelimitedLine(String line, char separator) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == separator) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private static List<String> normalizeHeaders(List<String> headers) {
        List<String> out = new ArrayList<>(headers.size());
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String raw = headers.get(i) == null ? "" : headers.get(i).strip();
            String name = raw.isEmpty() ? "column_" + (i + 1) : raw;
            String unique = name;
            int n = 2;
            while (!seen.add(unique.toLowerCase(Locale.ROOT))) {
                unique = name + "_" + n++;
            }
            out.add(unique);
        }
        return out;
    }

    private static List<String> syntheticHeaders(int width) {
        List<String> out = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            out.add("column_" + (i + 1));
        }
        return out;
    }

    private static void pad(List<String> row, int width) {
        while (row.size() < width) {
            row.add(null);
        }
    }

    private static long countLines(Path path) throws IOException {
        try (var stream = Files.lines(path, StandardCharsets.UTF_8)) {
            return stream.count();
        }
    }
}
