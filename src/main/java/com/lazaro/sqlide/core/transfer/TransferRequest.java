package com.lazaro.sqlide.core.transfer;

import com.lazaro.sqlide.core.db.ConnectionConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fully specified table-to-table transfer job.
 *
 * @param columnMapping source column name → target column name (order preserved)
 */
public record TransferRequest(
        ConnectionConfig sourceConfig,
        String sourceCatalog,
        String sourceTable,
        List<String> sourceColumns,
        ConnectionConfig targetConfig,
        String targetCatalog,
        String targetTable,
        Map<String, String> columnMapping,
        boolean truncateTarget,
        ErrorHandling errorHandling,
        int batchSize,
        long expectedRowCount
) {
    public enum ErrorHandling {
        ABORT,
        SKIP
    }

    public enum Strategy {
        SAME_CONNECTION,
        CROSS_CONNECTION
    }

    public TransferRequest {
        Objects.requireNonNull(sourceConfig, "sourceConfig");
        Objects.requireNonNull(targetConfig, "targetConfig");
        Objects.requireNonNull(sourceTable, "sourceTable");
        Objects.requireNonNull(targetTable, "targetTable");
        sourceColumns = List.copyOf(Objects.requireNonNullElse(sourceColumns, List.of()));
        columnMapping = new LinkedHashMap<>(Objects.requireNonNullElse(columnMapping, Map.of()));
        Objects.requireNonNull(errorHandling, "errorHandling");
        batchSize = Math.max(1, batchSize);
        sourceCatalog = blankToNull(sourceCatalog);
        targetCatalog = blankToNull(targetCatalog);
    }

    public Strategy resolveStrategy() {
        return sameServer(sourceConfig, targetConfig) ? Strategy.SAME_CONNECTION : Strategy.CROSS_CONNECTION;
    }

    public List<String> orderedSourceColumns() {
        return List.copyOf(columnMapping.keySet());
    }

    public List<String> orderedTargetColumns() {
        return columnMapping.values().stream().toList();
    }

    /** Mapping preserving insertion order for SQL generation. */
    public LinkedHashMap<String, String> orderedMapping() {
        return new LinkedHashMap<>(columnMapping);
    }

    public static boolean sameServer(ConnectionConfig a, ConnectionConfig b) {
        if (a == null || b == null) {
            return false;
        }
        return a.driver() == b.driver()
                && a.host().equalsIgnoreCase(b.host())
                && a.port() == b.port()
                && a.user().equals(b.user());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
