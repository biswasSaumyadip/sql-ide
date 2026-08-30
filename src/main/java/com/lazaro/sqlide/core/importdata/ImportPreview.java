package com.lazaro.sqlide.core.importdata;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Parsed file preview used by the Import wizard (first N rows only).
 *
 * @param path         source file
 * @param format       concrete format used to parse
 * @param columnNames  header names (synthetic {@code column_1…} when no header)
 * @param rows         preview rows (string cells; {@code null} = empty / JSON null)
 * @param totalRowsHint estimated total data rows when known, else {@code -1}
 */
public record ImportPreview(
        Path path,
        ImportFormat format,
        List<String> columnNames,
        List<List<String>> rows,
        long totalRowsHint
) {
    public ImportPreview {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(format, "format");
        columnNames = List.copyOf(Objects.requireNonNullElse(columnNames, List.of()));
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public int columnCount() {
        return columnNames.size();
    }
}
