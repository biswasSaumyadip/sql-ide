package com.lazaro.sqlide.core.runconfig;

import com.lazaro.sqlide.core.AppPaths;
import com.lazaro.sqlide.core.io.SimpleJson;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Named run configurations under {@code ~/.sql-ide/run-configurations.json}.
 */
public final class RunConfigurationStore {

    private final Path file;
    private final CopyOnWriteArrayList<RunConfiguration> configs = new CopyOnWriteArrayList<>();

    public RunConfigurationStore() {
        this(AppPaths.runConfigurationsFile());
    }

    public RunConfigurationStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        loadQuietly();
    }

    public List<RunConfiguration> all() {
        List<RunConfiguration> sorted = new ArrayList<>(configs);
        sorted.sort(Comparator.comparing(RunConfiguration::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    public List<RunConfiguration> search(String query) {
        if (query == null || query.isBlank()) {
            return all();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<RunConfiguration> matched = new ArrayList<>();
        for (RunConfiguration config : all()) {
            if (config.name().toLowerCase(Locale.ROOT).contains(needle)
                    || config.sql().toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(config);
            }
        }
        return List.copyOf(matched);
    }

    public RunConfiguration save(RunConfiguration config) {
        Objects.requireNonNull(config, "config");
        configs.removeIf(existing -> existing.id().equals(config.id()));
        configs.add(config);
        saveQuietly();
        return config;
    }

    public RunConfiguration save(String id, String name, String sql, String profileId, Map<String, String> params) {
        String resolvedId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        RunConfiguration config = new RunConfiguration(
                resolvedId, name, sql, profileId, params == null ? Map.of() : params, Instant.now());
        return save(config);
    }

    public Optional<RunConfiguration> find(String id) {
        return configs.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public void delete(String id) {
        configs.removeIf(config -> config.id().equals(id));
        saveQuietly();
    }

    private void loadQuietly() {
        try {
            List<Map<String, Object>> rows = SimpleJson.readObjectArray(file);
            List<RunConfiguration> loaded = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Instant at;
                try {
                    at = Instant.parse(String.valueOf(row.getOrDefault("updatedAt", Instant.EPOCH.toString())));
                } catch (RuntimeException e) {
                    at = Instant.EPOCH;
                }
                Map<String, String> params = new LinkedHashMap<>();
                Object rawParams = row.get("defaultParams");
                if (rawParams instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() != null) {
                            params.put(String.valueOf(entry.getKey()),
                                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                        }
                    }
                }
                loaded.add(new RunConfiguration(
                        String.valueOf(row.getOrDefault("id", UUID.randomUUID().toString())),
                        String.valueOf(row.getOrDefault("name", "")),
                        String.valueOf(row.getOrDefault("sql", "")),
                        String.valueOf(row.getOrDefault("profileId", "")),
                        params,
                        at));
            }
            configs.clear();
            configs.addAll(loaded);
        } catch (RuntimeException | IOException ignored) {
            configs.clear();
        }
    }

    private void saveQuietly() {
        try {
            List<Map<String, Object>> rows = new ArrayList<>(configs.size());
            for (RunConfiguration config : configs) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", config.id());
                map.put("name", config.name());
                map.put("sql", config.sql());
                map.put("profileId", config.profileId());
                map.put("defaultParams", new LinkedHashMap<>(config.defaultParams()));
                map.put("updatedAt", config.updatedAt().toString());
                rows.add(map);
            }
            SimpleJson.writeObjectArray(file, rows);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
