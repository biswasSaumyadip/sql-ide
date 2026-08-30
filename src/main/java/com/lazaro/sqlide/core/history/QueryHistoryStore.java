package com.lazaro.sqlide.core.history;

import com.lazaro.sqlide.core.AppPaths;
import com.lazaro.sqlide.core.io.SimpleJson;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persisted query history under {@code ~/.sql-ide/query-history.json}. Newest first.
 * Thread-safe for concurrent readers; writes replace the whole file.
 */
public final class QueryHistoryStore {

    public record Entry(
            String id,
            Instant executedAt,
            String sql,
            String summary,
            boolean success,
            long durationMs
    ) {
        public Entry {
            id = Objects.requireNonNullElse(id, UUID.randomUUID().toString());
            executedAt = Objects.requireNonNullElse(executedAt, Instant.now());
            sql = Objects.requireNonNullElse(sql, "");
            summary = Objects.requireNonNullElse(summary, "");
        }

        public String preview(int maxChars) {
            String oneLine = sql.replace('\n', ' ').replace('\r', ' ').strip();
            if (oneLine.length() <= maxChars) {
                return oneLine;
            }
            return oneLine.substring(0, Math.max(0, maxChars - 1)) + "\u2026";
        }
    }

    private static final int MAX_ENTRIES = 500;

    private final Path file;
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    public QueryHistoryStore() {
        this(AppPaths.historyFile());
    }

    public QueryHistoryStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        loadQuietly();
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<Entry> search(String query) {
        if (query == null || query.isBlank()) {
            return entries();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<Entry> matched = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.sql().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.summary().toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(entry);
            }
        }
        return List.copyOf(matched);
    }

    public Entry record(String sql, String summary, boolean success, long durationMs) {
        Entry entry = new Entry(UUID.randomUUID().toString(), Instant.now(), sql, summary, success, durationMs);
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        saveQuietly();
        return entry;
    }

    public void clear() {
        entries.clear();
        saveQuietly();
    }

    public void delete(String id) {
        entries.removeIf(entry -> entry.id().equals(id));
        saveQuietly();
    }

    private void loadQuietly() {
        try {
            List<Map<String, Object>> rows = SimpleJson.readObjectArray(file);
            List<Entry> loaded = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                loaded.add(fromMap(row));
            }
            entries.clear();
            entries.addAll(loaded);
        } catch (RuntimeException | IOException ignored) {
            entries.clear();
        }
    }

    private void saveQuietly() {
        try {
            List<Map<String, Object>> rows = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                rows.add(toMap(entry));
            }
            SimpleJson.writeObjectArray(file, rows);
        } catch (IOException ignored) {
            // persistence is best-effort; UI keeps the in-memory list
        }
    }

    private static Map<String, Object> toMap(Entry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.id());
        map.put("executedAt", entry.executedAt().toString());
        map.put("sql", entry.sql());
        map.put("summary", entry.summary());
        map.put("success", entry.success());
        map.put("durationMs", entry.durationMs());
        return map;
    }

    private static Entry fromMap(Map<String, Object> map) {
        Instant at;
        try {
            at = Instant.parse(String.valueOf(map.getOrDefault("executedAt", Instant.EPOCH.toString())));
        } catch (RuntimeException e) {
            at = Instant.EPOCH;
        }
        Object success = map.get("success");
        Object duration = map.get("durationMs");
        return new Entry(
                String.valueOf(map.getOrDefault("id", UUID.randomUUID().toString())),
                at,
                String.valueOf(map.getOrDefault("sql", "")),
                String.valueOf(map.getOrDefault("summary", "")),
                success instanceof Boolean b ? b : !"false".equalsIgnoreCase(String.valueOf(success)),
                duration instanceof Number n ? n.longValue() : 0L);
    }
}
