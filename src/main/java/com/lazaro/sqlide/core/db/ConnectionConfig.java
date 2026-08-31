package com.lazaro.sqlide.core.db;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a data-source endpoint. Carries the credentials needed
 * to open a pool (JDBC or Redis), but never exposes the password through
 * {@link #toString()}.
 */
public record ConnectionConfig(
        String host,
        int port,
        String database,
        String user,
        String password,
        Driver driver,
        Environment environment,
        Map<String, String> jdbcProperties,
        TunnelSettings tunnel
) {

    /**
     * High-level backend: relational SQL vs Redis. Derived from {@link Driver}.
     */
    public enum ConnectionType {
        MYSQL("MySQL"),
        REDIS("Redis");

        private final String displayName;

        ConnectionType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public boolean isRedis() {
            return this == REDIS;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * Supported dialects. MySQL and H2 ship with the application; MariaDB and
     * PostgreSQL work only if their driver jar is added to the classpath. Redis
     * uses Jedis rather than JDBC.
     */
    public enum Driver {
        MYSQL("MySQL", "com.mysql.cj.jdbc.Driver", 3306, "jdbc:mysql://%s:%d/%s"),
        MARIADB("MariaDB", "org.mariadb.jdbc.Driver", 3306, "jdbc:mariadb://%s:%d/%s"),
        POSTGRESQL("PostgreSQL", "org.postgresql.Driver", 5432, "jdbc:postgresql://%s:%d/%s"),
        /** Embedded scratch database. Host and port are ignored; only the name matters. */
        H2_MEMORY("H2 (in-memory)", "org.h2.Driver", 9092, "jdbc:h2:mem:%3$s;DB_CLOSE_DELAY=-1"),
        REDIS("Redis", "", 6379, "redis://%s:%d");

        private final String displayName;
        private final String driverClassName;
        private final int defaultPort;
        private final String urlTemplate;

        Driver(String displayName, String driverClassName, int defaultPort, String urlTemplate) {
            this.displayName = displayName;
            this.driverClassName = driverClassName;
            this.defaultPort = defaultPort;
            this.urlTemplate = urlTemplate;
        }

        public String displayName() {
            return displayName;
        }

        public String driverClassName() {
            return driverClassName;
        }

        public int defaultPort() {
            return defaultPort;
        }

        public ConnectionType connectionType() {
            return this == REDIS ? ConnectionType.REDIS : ConnectionType.MYSQL;
        }

        public boolean isJdbc() {
            return this != REDIS;
        }

        String jdbcUrl(String host, int port, String database) {
            if (this == REDIS) {
                return "redis://%s:%d".formatted(host, port);
            }
            return urlTemplate.formatted(host, port, database);
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * Workspace color tag so production sessions can be visually distinguished
     * from local / staging (editor chrome, result grid).
     */
    public enum Environment {
        NONE("None", "#7f848e"),
        LOCAL("Local", "#3d8c40"),
        STAGING("Staging", "#c9a227"),
        PRODUCTION("Production", "#c42b1c");

        private final String displayName;
        private final String colorHex;

        Environment(String displayName, String colorHex) {
            this.displayName = displayName;
            this.colorHex = colorHex;
        }

        public String displayName() {
            return displayName;
        }

        public String colorHex() {
            return colorHex;
        }

        public static Environment parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return NONE;
            }
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** SSH / SSL form values. Scaffolding for a future tunnel implementation. */
    public record TunnelSettings(
            boolean sshEnabled,
            String sshHost,
            int sshPort,
            String sshUser,
            String sshPrivateKeyPath,
            boolean sslEnabled,
            String sslCaCertPath,
            String sslClientCertPath
    ) {
        public TunnelSettings {
            sshHost = Objects.requireNonNullElse(sshHost, "").trim();
            sshPort = sshPort < 1 || sshPort > 65_535 ? 22 : sshPort;
            sshUser = Objects.requireNonNullElse(sshUser, "").trim();
            sshPrivateKeyPath = Objects.requireNonNullElse(sshPrivateKeyPath, "").trim();
            sslCaCertPath = Objects.requireNonNullElse(sslCaCertPath, "").trim();
            sslClientCertPath = Objects.requireNonNullElse(sslClientCertPath, "").trim();
        }

        public static TunnelSettings disabled() {
            return new TunnelSettings(false, "", 22, "", "", false, "", "");
        }
    }

    public ConnectionConfig(
            String host,
            int port,
            String database,
            String user,
            String password,
            Driver driver) {
        this(host, port, database, user, password, driver, Environment.NONE, Map.of(), TunnelSettings.disabled());
    }

    public ConnectionConfig {
        Objects.requireNonNull(driver, "driver must not be null");
        host = requireText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535 but was " + port);
        }
        database = Objects.requireNonNullElse(database, "").trim();
        user = Objects.requireNonNullElse(user, "").trim();
        password = Objects.requireNonNullElse(password, "");
        environment = environment == null ? Environment.NONE : environment;
        jdbcProperties = copyProperties(jdbcProperties);
        tunnel = tunnel == null ? TunnelSettings.disabled() : tunnel;
    }

    /** Convenience factory for the default MySQL endpoint. */
    public static ConnectionConfig mysql(String host, int port, String database, String user, String password) {
        return new ConnectionConfig(host, port, database, user, password, Driver.MYSQL);
    }

    /** Convenience factory for Redis (database/user optional). */
    public static ConnectionConfig redis(String host, int port, String password) {
        return new ConnectionConfig(host, port, "", "", password, Driver.REDIS);
    }

    public ConnectionType connectionType() {
        return driver.connectionType();
    }

    public String jdbcUrl() {
        return previewUrl(driver, host, port, database, jdbcProperties);
    }

    /**
     * JDBC / Redis URI for the live preview. Tolerates incomplete form input
     * (blank host, invalid port) so the dialog can update as the user types.
     */
    public static String previewUrl(
            Driver driver,
            String host,
            String portText,
            String database,
            Map<String, String> jdbcProperties) {
        Driver resolved = driver == null ? Driver.MYSQL : driver;
        String endpoint = host == null || host.isBlank() ? "localhost" : host.trim();
        int port = parsePreviewPort(portText, resolved.defaultPort());
        String schema = database == null ? "" : database.trim();
        return previewUrl(resolved, endpoint, port, schema, jdbcProperties);
    }

    public static String previewUrl(
            Driver driver,
            String host,
            int port,
            String database,
            Map<String, String> jdbcProperties) {
        Driver resolved = driver == null ? Driver.MYSQL : driver;
        String base = resolved.jdbcUrl(host, port, database == null ? "" : database);
        if (!resolved.isJdbc()) {
            return base;
        }
        return appendJdbcProperties(base, jdbcProperties);
    }

    /** Human readable identity of this connection, safe to render in the UI. */
    public String displayLabel() {
        if (driver == Driver.REDIS) {
            return "redis://%s:%d".formatted(host, port);
        }
        String schema = database.isEmpty() ? "" : "/" + database;
        return "%s@%s:%d%s".formatted(user.isEmpty() ? "<anonymous>" : user, host, port, schema);
    }

    /** Endpoint without a database suffix — for the status bar when the DB is shown separately. */
    public String endpointLabel() {
        if (driver == Driver.REDIS) {
            return "redis://%s:%d".formatted(host, port);
        }
        return "%s@%s:%d".formatted(user.isEmpty() ? "<anonymous>" : user, host, port);
    }

    @Override
    public String toString() {
        return "ConnectionConfig[%s, driver=%s, password=****]".formatted(displayLabel(), driver.displayName());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Map<String, String> copyProperties(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            copy.put(entry.getKey().trim(), Objects.requireNonNullElse(entry.getValue(), ""));
        }
        return Map.copyOf(copy);
    }

    private static int parsePreviewPort(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port >= 1 && port <= 65_535 ? port : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static String appendJdbcProperties(String url, Map<String, String> properties) {
        if (url == null || properties == null || properties.isEmpty()) {
            return url;
        }
        boolean h2 = url.startsWith("jdbc:h2:");
        StringBuilder out = new StringBuilder(url);
        boolean firstQuery = !url.contains("?");
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String key = entry.getKey().trim();
            String value = Objects.requireNonNullElse(entry.getValue(), "");
            if (h2) {
                out.append(';').append(key).append('=').append(value);
            } else {
                out.append(firstQuery ? '?' : '&');
                firstQuery = false;
                out.append(encodeQuery(key)).append('=').append(encodeQuery(value));
            }
        }
        return out.toString();
    }

    private static String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
