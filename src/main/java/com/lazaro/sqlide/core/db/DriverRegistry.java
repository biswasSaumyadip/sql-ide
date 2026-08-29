package com.lazaro.sqlide.core.db;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Maps a driver id to a factory, so the UI can open a data source without naming
 * a concrete implementation class.
 */
public final class DriverRegistry {

    private final Map<String, Supplier<DataSourceDriver>> factories = new LinkedHashMap<>();

    /** Registry pre-populated with the drivers that ship with the application. */
    public static DriverRegistry withDefaults() {
        DriverRegistry registry = new DriverRegistry();
        registry.register(JdbcSqlDriver.ID, JdbcSqlDriver::new);
        return registry;
    }

    public void register(String id, Supplier<DataSourceDriver> factory) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        factories.put(id, factory);
    }

    /**
     * Builds a new, unconnected driver.
     *
     * @throws IllegalArgumentException if no driver is registered under {@code id}
     */
    public DataSourceDriver create(String id) {
        Supplier<DataSourceDriver> factory = factories.get(id);
        if (factory == null) {
            throw new IllegalArgumentException("No driver registered under id '" + id + "'. Known ids: " + ids());
        }
        return factory.get();
    }

    public boolean isRegistered(String id) {
        return factories.containsKey(id);
    }

    /** Registered ids, in registration order. */
    public Set<String> ids() {
        return Set.copyOf(factories.keySet());
    }
}
