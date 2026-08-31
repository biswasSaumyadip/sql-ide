package com.lazaro.sqlide.core.db;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * JDBC {@code DatabaseMetaData} takes {@code (catalog, schema)}. MySQL and H2 put
 * the database name in the catalog slot; some PostgreSQL-style drivers put it in
 * schema and leave catalogs empty.
 *
 * <p>Probe catalog first, then schema if empty, and remember which slot produced
 * rows so later calls on this connection skip the extra round-trip. An empty
 * result does <em>not</em> flip the layout — an empty database is not a schema
 * server.
 */
public final class JdbcMetadataLayout {

    public enum Slot {
        CATALOG,
        SCHEMA
    }

    @FunctionalInterface
    public interface Read<T> {
        T get(String catalog, String schema) throws SQLException;
    }

    private final AtomicReference<Slot> slot = new AtomicReference<>();

    public Optional<Slot> slot() {
        return Optional.ofNullable(slot.get());
    }

    public void clear() {
        slot.set(null);
    }

    /** Records a layout discovered from {@code getCatalogs}/{@code getSchemas}. */
    public void remember(Slot discovered) {
        if (discovered != null) {
            slot.compareAndSet(null, discovered);
        }
    }

    /**
     * Reads with the cached slot. If unknown, tries catalog then schema and
     * remembers the first non-empty hit.
     */
    public <T> T read(String owner, Read<T> read, Predicate<T> empty) throws SQLException {
        return read(owner, read, empty, true);
    }

    /**
     * Like {@link #read} but never learns from a hit. Use for lookups that are
     * often empty on a healthy catalog (no PK, no FKs) so a miss cannot be
     * mistaken for the other layout.
     */
    public <T> T probe(String owner, Read<T> read, Predicate<T> empty) throws SQLException {
        return read(owner, read, empty, false);
    }

    static boolean isEmpty(Collection<?> value) {
        return value == null || value.isEmpty();
    }

    static boolean isEmpty(Map<?, ?> value) {
        return value == null || value.isEmpty();
    }

    private <T> T read(String owner, Read<T> read, Predicate<T> empty, boolean rememberHit)
            throws SQLException {
        Slot known = slot.get();
        if (known == Slot.SCHEMA) {
            return read.get(null, owner);
        }
        if (known == Slot.CATALOG) {
            return read.get(owner, null);
        }
        T catalogHit = read.get(owner, null);
        if (!empty.test(catalogHit)) {
            if (rememberHit) {
                slot.compareAndSet(null, Slot.CATALOG);
            }
            return catalogHit;
        }
        T schemaHit = read.get(null, owner);
        if (!empty.test(schemaHit)) {
            if (rememberHit) {
                slot.compareAndSet(null, Slot.SCHEMA);
            }
            return schemaHit;
        }
        return catalogHit;
    }
}
