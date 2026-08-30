package com.lazaro.sqlide.ui.components;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds one {@link SchemaSelectionState} per data-source / connection id so the
 * schema picker does not leak selections across connections.
 */
final class SchemaSelectionRegistry {

    private final Map<String, SchemaSelectionState> states = new LinkedHashMap<>();

    SchemaSelectionState forConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return new SchemaSelectionState();
        }
        return states.computeIfAbsent(connectionId, key -> new SchemaSelectionState());
    }

    boolean hasConnection(String connectionId) {
        return connectionId != null && states.containsKey(connectionId);
    }

    void remove(String connectionId) {
        if (connectionId != null) {
            states.remove(connectionId);
        }
    }

    void clear() {
        states.clear();
    }
}
