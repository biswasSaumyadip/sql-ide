package com.lazaro.sqlide.core.db;

import java.util.List;
import java.util.Objects;

/**
 * A table or view. Columns are empty until they are fetched, which lets the UI
 * expand the schema tree lazily instead of introspecting an entire server upfront.
 *
 * @param catalog owning catalog (database) name
 * @param name    table name
 * @param type    JDBC table type, e.g. {@code TABLE} or {@code VIEW}
 * @param columns columns of this table, possibly empty
 */
public record TableNode(String catalog, String name, String type, List<ColumnNode> columns) {

    public TableNode {
        name = Objects.requireNonNull(name, "name must not be null");
        catalog = Objects.requireNonNullElse(catalog, "");
        type = Objects.requireNonNullElse(type, "TABLE");
        columns = List.copyOf(Objects.requireNonNullElse(columns, List.of()));
    }

    public static TableNode of(String catalog, String name, String type) {
        return new TableNode(catalog, name, type, List.of());
    }

    public TableNode withColumns(List<ColumnNode> newColumns) {
        return new TableNode(catalog, name, type, newColumns);
    }

    public boolean isView() {
        return type.contains("VIEW");
    }

    /** Name usable in a statement, e.g. {@code sales.orders}. */
    public String qualifiedName() {
        return catalog.isEmpty() ? name : catalog + "." + name;
    }
}
