package com.lazaro.sqlide.core.session;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import com.lazaro.sqlide.core.db.SchemaCache;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One live database session: driver pool, schema cache, and display identity.
 */
public final class ConnectionSession implements AutoCloseable {

    private final String id;
    private final String profileId;
    private final String displayName;
    private final DataSourceDriver driver;
    private final SchemaCache schemaCache = new SchemaCache();
    private ConnectionConfig config;

    public ConnectionSession(
            String id,
            String profileId,
            String displayName,
            DataSourceDriver driver,
            ConnectionConfig config) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.profileId = profileId == null || profileId.isBlank() ? null : profileId;
        this.displayName = Objects.requireNonNullElse(displayName, "Session").strip();
        this.driver = Objects.requireNonNull(driver, "driver");
        this.config = Objects.requireNonNull(config, "config");
    }

    public String id() {
        return id;
    }

    /** Saved {@link com.lazaro.sqlide.core.config.ConnectionProfile} id, or empty for ephemeral. */
    public Optional<String> profileId() {
        return Optional.ofNullable(profileId);
    }

    public String displayName() {
        return displayName;
    }

    public DataSourceDriver driver() {
        return driver;
    }

    public SchemaCache schemaCache() {
        return schemaCache;
    }

    public ConnectionConfig config() {
        return config;
    }

    void updateConfig(ConnectionConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public boolean isConnected() {
        return driver.isConnected();
    }

    /** Short label for combo boxes: {@code name — user@host:port/db}. */
    public String comboLabel() {
        return displayName + " \u2014 " + config.displayLabel();
    }

    @Override
    public void close() {
        driver.close();
        schemaCache.clear();
    }

    @Override
    public String toString() {
        return comboLabel();
    }
}
