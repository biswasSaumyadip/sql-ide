package com.lazaro.sqlide.core.transfer;

import com.lazaro.sqlide.core.db.ConnectionConfig;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Low-level JDBC helpers used by transfer (off the FX thread). */
public final class TransferJdbc {

    private TransferJdbc() {
    }

    public static Connection open(ConnectionConfig config) throws SQLException {
        Objects.requireNonNull(config, "config");
        try {
            Class.forName(config.driver().driverClassName());
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not on the classpath: " + config.driver().driverClassName(), e);
        }
        Connection connection = DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password());
        if (config.database() != null && !config.database().isBlank()) {
            try {
                connection.setCatalog(config.database());
            } catch (SQLException ignored) {
                // Some drivers ignore setCatalog; qualified names still work.
            }
        }
        return connection;
    }

    public static List<String> listCatalogs(Connection connection) throws SQLException {
        List<String> catalogs = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getCatalogs()) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && !name.isBlank()) {
                    catalogs.add(name);
                }
            }
        }
        if (catalogs.isEmpty()) {
            try (ResultSet rs = meta.getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_SCHEM");
                    if (name != null && !name.isBlank()) {
                        catalogs.add(name);
                    }
                }
            }
        }
        catalogs.sort(String.CASE_INSENSITIVE_ORDER);
        return catalogs;
    }

    public static List<String> listTables(Connection connection, String catalog) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        String cat = blankToNull(catalog);
        try (ResultSet rs = meta.getTables(cat, null, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !name.isBlank()) {
                    tables.add(name);
                }
            }
        }
        if (tables.isEmpty()) {
            try (ResultSet rs = meta.getTables(null, cat, "%", new String[]{"TABLE", "BASE TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name != null && !name.isBlank() && !tables.contains(name)) {
                        tables.add(name);
                    }
                }
            }
        }
        tables.sort(String.CASE_INSENSITIVE_ORDER);
        return tables;
    }

    public static List<String> listColumns(Connection connection, String catalog, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        String cat = blankToNull(catalog);
        try (ResultSet rs = meta.getColumns(cat, null, table, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        if (columns.isEmpty()) {
            try (ResultSet rs = meta.getColumns(null, cat, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    if (!columns.contains(name)) {
                        columns.add(name);
                    }
                }
            }
        }
        return columns;
    }

    public static long countRows(Connection connection, String catalog, String table, ConnectionConfig.Driver driver)
            throws SQLException {
        String sql = TransferSql.count(catalog, table, driver);
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return -1;
    }

    public static boolean looksLikeSystemCatalog(String name) {
        if (name == null) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("information_schema")
                || n.equals("mysql")
                || n.equals("performance_schema")
                || n.equals("sys")
                || n.equals("pg_catalog")
                || n.equals("pg_toast");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
