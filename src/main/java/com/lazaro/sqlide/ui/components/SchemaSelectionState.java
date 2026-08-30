package com.lazaro.sqlide.ui.components;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure selection state for the IntelliJ-style schema filter ({@code N of M}).
 */
final class SchemaSelectionState {

    private final List<String> available = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private boolean initialized;

    /**
     * Applies a previously persisted selection before catalogs are loaded.
     * Marks the state initialized so the next {@link #setAvailableSchemas}
     * keeps these names (pruned to whatever is available) instead of preferred.
     */
    void applyRestoredSelection(Collection<String> names) {
        selected.clear();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    selected.add(name.strip());
                }
            }
        }
        initialized = true;
    }

    boolean isInitialized() {
        return initialized;
    }

    void setAvailableSchemas(List<String> schemas, String preferredActive) {
        available.clear();
        if (schemas != null) {
            for (String schema : schemas) {
                if (schema != null && !schema.isBlank()) {
                    available.add(schema);
                }
            }
        }

        if (!initialized) {
            selected.clear();
            String preferred = preferredActive == null ? "" : preferredActive.strip();
            if (!preferred.isEmpty()) {
                available.stream()
                        .filter(name -> name.equalsIgnoreCase(preferred))
                        .findFirst()
                        .ifPresentOrElse(selected::add, () -> selected.addAll(available));
            } else {
                selected.addAll(available);
            }
            initialized = !available.isEmpty();
        } else {
            selected.removeIf(name -> available.stream().noneMatch(a -> a.equalsIgnoreCase(name)));
        }
    }

    void clear() {
        available.clear();
        selected.clear();
        initialized = false;
    }

    void selectAll() {
        selected.clear();
        selected.addAll(available);
    }

    void selectNone() {
        selected.clear();
    }

    void setSelected(String schema, boolean selectedFlag) {
        if (schema == null || schema.isBlank()) {
            return;
        }
        if (selectedFlag) {
            available.stream()
                    .filter(name -> name.equalsIgnoreCase(schema))
                    .findFirst()
                    .ifPresent(selected::add);
        } else {
            selected.removeIf(s -> s.equalsIgnoreCase(schema));
        }
    }

    List<String> available() {
        return List.copyOf(available);
    }

    Set<String> selected() {
        return Set.copyOf(selected);
    }

    boolean isSchemaVisible(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        if (available.isEmpty()) {
            return true;
        }
        if (selected.isEmpty()) {
            return false;
        }
        return selected.stream().anyMatch(s -> s.equalsIgnoreCase(name));
    }

    int availableCount() {
        return available.size();
    }

    int selectedCount() {
        int count = 0;
        for (String schema : available) {
            if (isSchemaVisible(schema)) {
                count++;
            }
        }
        return count;
    }

    List<String> filteredAvailable(String needle) {
        String query = needle == null ? "" : needle.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return available();
        }
        List<String> matched = new ArrayList<>();
        for (String schema : available) {
            if (schema.toLowerCase(Locale.ROOT).contains(query)) {
                matched.add(schema);
            }
        }
        return List.copyOf(matched);
    }
}
