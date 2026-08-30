package com.lazaro.sqlide.core.snippets;

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
 * Saved SQL templates under {@code ~/.sql-ide/snippets.json}.
 */
public final class SnippetStore {

    public record Snippet(String id, String name, String sql, Instant updatedAt) {
        public Snippet {
            id = Objects.requireNonNullElse(id, UUID.randomUUID().toString());
            name = Objects.requireNonNullElse(name, "").strip();
            sql = Objects.requireNonNullElse(sql, "");
            updatedAt = Objects.requireNonNullElse(updatedAt, Instant.now());
        }
    }

    private final Path file;
    private final CopyOnWriteArrayList<Snippet> snippets = new CopyOnWriteArrayList<>();

    public SnippetStore() {
        this(AppPaths.snippetsFile());
    }

    public SnippetStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        loadQuietly();
    }

    public List<Snippet> snippets() {
        List<Snippet> sorted = new ArrayList<>(snippets);
        sorted.sort(Comparator.comparing(Snippet::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    public List<Snippet> search(String query) {
        if (query == null || query.isBlank()) {
            return snippets();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<Snippet> matched = new ArrayList<>();
        for (Snippet snippet : snippets()) {
            if (snippet.name().toLowerCase(Locale.ROOT).contains(needle)
                    || snippet.sql().toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(snippet);
            }
        }
        return List.copyOf(matched);
    }

    public Snippet save(String id, String name, String sql) {
        String resolvedId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        Snippet snippet = new Snippet(resolvedId, name, sql, Instant.now());
        snippets.removeIf(existing -> existing.id().equals(resolvedId));
        snippets.add(snippet);
        saveQuietly();
        return snippet;
    }

    public Optional<Snippet> find(String id) {
        return snippets.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public void delete(String id) {
        snippets.removeIf(snippet -> snippet.id().equals(id));
        saveQuietly();
    }

    private void loadQuietly() {
        try {
            List<Map<String, Object>> rows = SimpleJson.readObjectArray(file);
            List<Snippet> loaded = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Instant at;
                try {
                    at = Instant.parse(String.valueOf(row.getOrDefault("updatedAt", Instant.EPOCH.toString())));
                } catch (RuntimeException e) {
                    at = Instant.EPOCH;
                }
                loaded.add(new Snippet(
                        String.valueOf(row.getOrDefault("id", UUID.randomUUID().toString())),
                        String.valueOf(row.getOrDefault("name", "")),
                        String.valueOf(row.getOrDefault("sql", "")),
                        at));
            }
            snippets.clear();
            snippets.addAll(loaded);
        } catch (RuntimeException | IOException ignored) {
            snippets.clear();
        }
    }

    private void saveQuietly() {
        try {
            List<Map<String, Object>> rows = new ArrayList<>(snippets.size());
            for (Snippet snippet : snippets) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", snippet.id());
                map.put("name", snippet.name());
                map.put("sql", snippet.sql());
                map.put("updatedAt", snippet.updatedAt().toString());
                rows.add(map);
            }
            SimpleJson.writeObjectArray(file, rows);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
