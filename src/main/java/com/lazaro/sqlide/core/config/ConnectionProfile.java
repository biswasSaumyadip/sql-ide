package com.lazaro.sqlide.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lazaro.sqlide.core.db.ConnectionConfig.Environment;
import com.lazaro.sqlide.core.db.ConnectionConfig.TunnelSettings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Saved JDBC connection profile. Passwords are intentionally absent — never
 * serialize credentials into {@code connections.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionProfile(
        String id,
        String name,
        String driver,
        String host,
        int port,
        String database,
        String username,
        String environment,
        TunnelSettings tunnel,
        Map<String, String> jdbcProperties
) {

    public ConnectionProfile(
            String id,
            String name,
            String driver,
            String host,
            int port,
            String database,
            String username) {
        this(id, name, driver, host, port, database, username, Environment.NONE.name(),
                TunnelSettings.disabled(), Map.of());
    }

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
        environment = Environment.parse(environment).name();
        tunnel = tunnel == null ? TunnelSettings.disabled() : tunnel;
        jdbcProperties = copyProperties(jdbcProperties);
    }

    public Environment environmentTag() {
        return Environment.parse(environment);
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
}
