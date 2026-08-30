package com.lazaro.sqlide.core.importdata;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Supported import file formats. */
public enum ImportFormat {
    AUTO("Auto-detect"),
    CSV("CSV"),
    TSV("TSV"),
    JSON("JSON"),
    XLSX("Excel (.xlsx)");

    private final String label;

    ImportFormat(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static ImportFormat fromPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return AUTO;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            return CSV;
        }
        if (name.endsWith(".tsv") || name.endsWith(".tab")) {
            return TSV;
        }
        if (name.endsWith(".json")) {
            return JSON;
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return XLSX;
        }
        return AUTO;
    }

    public ImportFormat resolve(Path path) {
        return this == AUTO ? fromPath(path) : this;
    }

    public static ImportFormat requireConcrete(ImportFormat format, Path path) {
        ImportFormat resolved = Objects.requireNonNullElse(format, AUTO).resolve(path);
        if (resolved == AUTO) {
            throw new IllegalArgumentException("Could not detect file format from extension");
        }
        return resolved;
    }
}
