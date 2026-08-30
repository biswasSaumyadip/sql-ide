package com.lazaro.sqlide.core.runconfig;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Named script bound to a saved connection profile and optional default params.
 */
public record RunConfiguration(
        String id,
        String name,
        String sql,
        String profileId,
        Map<String, String> defaultParams,
        Instant updatedAt
) {
    public RunConfiguration {
        id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id.trim();
        name = Objects.requireNonNullElse(name, "").strip();
        sql = Objects.requireNonNullElse(sql, "");
        profileId = Objects.requireNonNullElse(profileId, "").strip();
        defaultParams = Map.copyOf(Objects.requireNonNullElse(defaultParams, Map.of()));
        updatedAt = Objects.requireNonNullElse(updatedAt, Instant.now());
    }

    public RunConfiguration withDefaults(Map<String, String> params) {
        return new RunConfiguration(id, name, sql, profileId,
                params == null ? Map.of() : new LinkedHashMap<>(params), Instant.now());
    }
}
