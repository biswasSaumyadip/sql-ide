package com.lazaro.sqlide.core.config;

import java.util.Objects;
import java.util.UUID;

/**
 * Saved JDBC connection profile. Passwords are intentionally absent — never
 * serialize credentials into {@code connections.json}.
 */
public record ConnectionProfile(
        String id,
        String name,
        String driver,
        String host,
        int port,
        String database,
        String username
) {

    public ConnectionProfile {
        id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id.trim();
        name = Objects.requireNonNullElse(name, "").trim();
        driver = Objects.requireNonNullElse(driver, "").trim();
        host = Objects.requireNonNullElse(host, "").trim();
        database = Objects.requireNonNullElse(database, "").trim();
        username = Objects.requireNonNullElse(username, "").trim();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535 but was " + port);
        }
    }

    /** Label shown in the saved-connections picker. */
    public String displayName() {
        if (!name.isBlank()) {
            return name;
        }
        String schema = database.isEmpty() ? "" : "/" + database;
        String user = username.isEmpty() ? "<anonymous>" : username;
        return "%s — %s@%s:%d%s".formatted(driverLabel(), user, host, port, schema);
    }

    private String driverLabel() {
        return driver.isBlank() ? "JDBC" : driver;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
