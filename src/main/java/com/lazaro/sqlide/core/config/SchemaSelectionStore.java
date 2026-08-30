package com.lazaro.sqlide.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists per-connection schema filter selections under
 * {@code ~/.sql-ide-config/schema-selections.json}.
 */
public final class SchemaSelectionStore {

    private static final Logger LOG = Logger.getLogger(SchemaSelectionStore.class.getName());

    private static final Path DEFAULT_FILE = Path.of(
            System.getProperty("user.home"), ".sql-ide-config", "schema-selections.json");

    private final Path file;
    private final ObjectMapper mapper;

    public SchemaSelectionStore() {
        this(DEFAULT_FILE);
    }

    public SchemaSelectionStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path storageFile() {
        return file;
    }

    /** connectionId → selected schema / database names (order preserved). */
    public Map<String, List<String>> loadAll() {
        try {
            ensureStorageExists();
            if (Files.size(file) == 0) {
                writeAll(Map.of());
                return Map.of();
            }
            Map<String, List<String>> loaded = mapper.readValue(file.toFile(), new TypeReference<>() {
            });
            if (loaded == null || loaded.isEmpty()) {
                return Map.of();
            }
            Map<String, List<String>> sanitized = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : loaded.entrySet()) {
                String id = entry.getKey();
                if (id == null || id.isBlank()) {
                    continue;
                }
                sanitized.put(id, sanitizeNames(entry.getValue()));
            }
            return Map.copyOf(sanitized);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to load schema selections from " + file, ex);
            return Map.of();
        }
    }

    public List<String> loadSelection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return List.of();
        }
        List<String> names = loadAll().get(connectionId);
        return names == null ? List.of() : names;
    }

    public boolean hasSelection(String connectionId) {
        return connectionId != null && loadAll().containsKey(connectionId);
    }

    /** Replaces the saved selection for {@code connectionId}. */
    public void saveSelection(String connectionId, Collection<String> selected) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        try {
            Map<String, List<String>> all = new LinkedHashMap<>(loadAllUnlocked());
            all.put(connectionId, sanitizeNames(selected));
            writeAll(all);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to save schema selection for " + connectionId, ex);
        }
    }

    public void remove(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        try {
            Map<String, List<String>> all = new LinkedHashMap<>(loadAllUnlocked());
            if (all.remove(connectionId) != null) {
                writeAll(all);
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to delete schema selection for " + connectionId, ex);
        }
    }

    private static List<String> sanitizeNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                unique.add(name.strip());
            }
        }
        return List.copyOf(unique);
    }

    private Map<String, List<String>> loadAllUnlocked() throws IOException {
        ensureStorageExists();
        if (Files.size(file) == 0) {
            return new LinkedHashMap<>();
        }
        Map<String, List<String>> loaded = mapper.readValue(file.toFile(), new TypeReference<>() {
        });
        return loaded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded);
    }

    private void ensureStorageExists() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(file)) {
            writeAll(Map.of());
        }
    }

    private void writeAll(Map<String, List<String>> selections) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Map<String, List<String>> payload = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : selections.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            payload.put(entry.getKey(), sanitizeNames(entry.getValue()));
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), payload);
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
