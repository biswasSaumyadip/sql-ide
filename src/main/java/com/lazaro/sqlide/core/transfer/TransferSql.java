package com.lazaro.sqlide.core.transfer;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.ConnectionConfig.Driver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/** Builds dialect-aware transfer SQL (no JDBC). */
public final class TransferSql {

    private TransferSql() {
    }

    public static String quote(String identifier, Driver driver) {
        Objects.requireNonNull(identifier, "identifier");
        Driver d = driver == null ? Driver.MYSQL : driver;
        return switch (d) {
            case POSTGRESQL -> "\"" + identifier.replace("\"", "\"\"") + "\"";
            case MYSQL, MARIADB, H2_MEMORY, REDIS -> "`" + identifier.replace("`", "``") + "`";
        };
    }

    public static String qualify(String catalog, String table, Driver driver) {
        Objects.requireNonNull(table, "table");
        if (catalog == null || catalog.isBlank()) {
            return quote(table, driver);
        }
        return quote(catalog, driver) + "." + quote(table, driver);
    }

    /**
     * {@code INSERT INTO target (tcols…) SELECT scols… FROM source}
     */
    public static String insertSelect(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        Driver driver = request.targetConfig().driver();
        Map<String, String> mapping = request.orderedMapping();
        if (mapping.isEmpty()) {
            throw new IllegalArgumentException("column mapping is empty");
        }
        StringJoiner targetCols = new StringJoiner(", ");
        StringJoiner sourceCols = new StringJoiner(", ");
        mapping.forEach((sourceCol, targetCol) -> {
            sourceCols.add(quote(sourceCol, driver));
            targetCols.add(quote(targetCol, driver));
        });
        return "INSERT INTO " + qualify(request.targetCatalog(), request.targetTable(), driver)
                + " (" + targetCols + ") SELECT " + sourceCols
                + " FROM " + qualify(request.sourceCatalog(), request.sourceTable(), driver);
    }

    public static String selectMapped(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        Driver driver = request.sourceConfig().driver();
        Map<String, String> mapping = request.orderedMapping();
        if (mapping.isEmpty()) {
            throw new IllegalArgumentException("column mapping is empty");
        }
        StringJoiner sourceCols = new StringJoiner(", ");
        mapping.keySet().forEach(sourceCol -> sourceCols.add(quote(sourceCol, driver)));
        return "SELECT " + sourceCols
                + " FROM " + qualify(request.sourceCatalog(), request.sourceTable(), driver);
    }

    public static String insertPlaceholders(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        Driver driver = request.targetConfig().driver();
        Map<String, String> mapping = request.orderedMapping();
        if (mapping.isEmpty()) {
            throw new IllegalArgumentException("column mapping is empty");
        }
        StringJoiner targetCols = new StringJoiner(", ");
        List<String> placeholders = new ArrayList<>(mapping.size());
        mapping.values().forEach(targetCol -> {
            targetCols.add(quote(targetCol, driver));
            placeholders.add("?");
        });
        return "INSERT INTO " + qualify(request.targetCatalog(), request.targetTable(), driver)
                + " (" + targetCols + ") VALUES (" + String.join(", ", placeholders) + ")";
    }

    public static String truncate(TransferRequest request) {
        Driver driver = request.targetConfig().driver();
        return "TRUNCATE TABLE " + qualify(request.targetCatalog(), request.targetTable(), driver);
    }

    public static String count(String catalog, String table, Driver driver) {
        return "SELECT COUNT(*) FROM " + qualify(catalog, table, driver);
    }
}
