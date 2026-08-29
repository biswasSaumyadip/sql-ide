package com.lazaro.sqlide.core.db;

import java.util.List;
import java.util.Objects;

/**
 * A catalog (MySQL "database") or, on servers that expose no catalogs, a schema.
 * Tables are empty until fetched, mirroring the lazy expansion of the schema tree.
 *
 * @param name   catalog or schema name
 * @param tables tables belonging to it, possibly empty
 */
public record DatabaseNode(String name, List<TableNode> tables) {

    public DatabaseNode {
        name = Objects.requireNonNull(name, "name must not be null");
        tables = List.copyOf(Objects.requireNonNullElse(tables, List.of()));
    }

    public static DatabaseNode of(String name) {
        return new DatabaseNode(name, List.of());
    }

    public DatabaseNode withTables(List<TableNode> newTables) {
        return new DatabaseNode(name, newTables);
    }
}
