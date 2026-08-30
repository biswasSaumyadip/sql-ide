package com.lazaro.sqlide.core.importdata;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Options chosen on the Import wizard Finish step.
 *
 * @param columnMapping source column index → target DB column name (unmapped sources omitted)
 */
public record ImportPlan(
        Path sourceFile,
        ImportFormat format,
        boolean firstRowIsHeader,
        String targetTableQualified,
        List<String> targetColumns,
        Map<Integer, String> columnMapping,
        boolean truncateBeforeImport,
        int batchSize,
        ErrorHandling errorHandling,
        ImportPreview preview
) {
    public enum ErrorHandling {
        ABORT("Abort on first error"),
        SKIP("Skip bad rows");

        private final String label;

        ErrorHandling(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public ImportPlan {
        Objects.requireNonNull(sourceFile, "sourceFile");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(targetTableQualified, "targetTableQualified");
        targetColumns = List.copyOf(Objects.requireNonNullElse(targetColumns, List.of()));
        columnMapping = Map.copyOf(Objects.requireNonNullElse(columnMapping, Map.of()));
        Objects.requireNonNull(errorHandling, "errorHandling");
        batchSize = Math.max(1, batchSize);
    }
}
