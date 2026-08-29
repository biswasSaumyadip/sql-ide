package com.lazaro.sqlide.core.db;

import java.util.Objects;

/**
 * What a {@link DataSourceDriver} can do. Lets the UI enable or hide features
 * without testing for a concrete driver class.
 *
 * @param id                  registry key, e.g. {@code jdbc-mysql}
 * @param displayName         label for menus and dialogs
 * @param supportsSchemaTree  whether {@link DataSourceDriver#getSchemaTree()} is meaningful
 * @param supportsTransactions whether commit and rollback apply
 * @param supportsCatalogs    whether the tree has a catalog level above tables
 * @param maxRowsPerQuery     cap the driver applies when materialising a result
 */
public record DriverCapabilities(
        String id,
        String displayName,
        boolean supportsSchemaTree,
        boolean supportsTransactions,
        boolean supportsCatalogs,
        int maxRowsPerQuery
) {

    public DriverCapabilities {
        id = Objects.requireNonNull(id, "id must not be null");
        displayName = Objects.requireNonNull(displayName, "displayName must not be null");
        if (maxRowsPerQuery < 1) {
            throw new IllegalArgumentException("maxRowsPerQuery must be positive but was " + maxRowsPerQuery);
        }
    }
}
