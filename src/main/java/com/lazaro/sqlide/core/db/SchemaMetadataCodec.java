package com.lazaro.sqlide.core.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes and decodes multi-valued schema facts into the flat {@link SchemaNode}
 * metadata map, so autocomplete and the object viewer share one representation.
 */
public final class SchemaMetadataCodec {

    private static final String ENTRY_SEP = ";";
    private static final String FIELD_SEP = "|";

    private SchemaMetadataCodec() {
    }

    // ---------------------------------------------------------------- foreign keys

    public record ForeignKey(
            String name, String fkColumn, String pkTable, String pkColumn, String onUpdate, String onDelete) {
        public ForeignKey(String name, String fkColumn, String pkTable, String pkColumn) {
            this(name, fkColumn, pkTable, pkColumn, "", "");
        }
    }

    public static String encodeForeignKeys(List<ForeignKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(keys.size());
        for (ForeignKey key : keys) {
            parts.add(String.join(FIELD_SEP,
                    safe(key.name()),
                    safe(key.fkColumn()),
                    safe(key.pkTable()),
                    safe(key.pkColumn()),
                    safe(key.onUpdate()),
                    safe(key.onDelete())));
        }
        return String.join(ENTRY_SEP, parts);
    }

    public static List<ForeignKey> decodeForeignKeys(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<ForeignKey> keys = new ArrayList<>();
        for (String entry : encoded.split(ENTRY_SEP)) {
            String[] fields = entry.split("\\" + FIELD_SEP, -1);
            if (fields.length >= 4) {
                keys.add(new ForeignKey(
                        fields[0],
                        fields[1],
                        fields[2],
                        fields[3],
                        fields.length > 4 ? fields[4] : "",
                        fields.length > 5 ? fields[5] : ""));
            }
        }
        return List.copyOf(keys);
    }

    // ---------------------------------------------------------------- indexes

    public record IndexInfo(String name, boolean unique, List<String> columns, String type) {
        public IndexInfo {
            columns = List.copyOf(Objects.requireNonNullElse(columns, List.of()));
            type = type == null || type.isBlank() ? "BTREE" : type;
        }

        public IndexInfo(String name, boolean unique, List<String> columns) {
            this(name, unique, columns, "BTREE");
        }
    }

    public static String encodeIndexes(List<IndexInfo> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(indexes.size());
        for (IndexInfo index : indexes) {
            parts.add(String.join(FIELD_SEP,
                    safe(index.name()),
                    index.unique() ? "UNIQUE" : "NON_UNIQUE",
                    String.join(",", index.columns()),
                    safe(index.type())));
        }
        return String.join(ENTRY_SEP, parts);
    }

    public static List<IndexInfo> decodeIndexes(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<IndexInfo> indexes = new ArrayList<>();
        for (String entry : encoded.split(ENTRY_SEP)) {
            String[] fields = entry.split("\\" + FIELD_SEP, -1);
            if (fields.length >= 3) {
                boolean unique = "UNIQUE".equalsIgnoreCase(fields[1]);
                List<String> columns = fields[2].isBlank()
                        ? List.of()
                        : List.of(fields[2].split(","));
                String type = fields.length > 3 && !fields[3].isBlank() ? fields[3] : "BTREE";
                indexes.add(new IndexInfo(fields[0], unique, columns, type));
            }
        }
        return List.copyOf(indexes);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace(ENTRY_SEP, "").replace(FIELD_SEP, "");
    }
}
