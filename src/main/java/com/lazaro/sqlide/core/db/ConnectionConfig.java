package com.lazaro.sqlide.core.db;

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
        Driver driver
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

    public ConnectionConfig {
        Objects.requireNonNull(driver, "driver must not be null");
        host = requireText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535 but was " + port);
        }
        database = Objects.requireNonNullElse(database, "").trim();
        user = Objects.requireNonNullElse(user, "").trim();
        password = Objects.requireNonNullElse(password, "");
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
        return driver.jdbcUrl(host, port, database);
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
}
