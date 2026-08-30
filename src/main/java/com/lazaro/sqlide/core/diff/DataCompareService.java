package com.lazaro.sqlide.core.diff;

import com.lazaro.sqlide.core.db.QueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Row-level comparison of two result sets keyed by chosen columns.
 */
public final class DataCompareService {

    public enum RowStatus {
        MATCH,
        CHANGED,
        LEFT_ONLY,
        RIGHT_ONLY
    }

    public record RowDiff(
            RowStatus status,
            String key,
            List<String> leftValues,
            List<String> rightValues,
            List<String> changedColumns
    ) {
    }

    public record DataDiff(
            List<String> columns,
            List<String> keyColumns,
            List<RowDiff> rows,
            int matchCount,
            int changedCount,
            int leftOnlyCount,
            int rightOnlyCount
    ) {
        public int totalDifferences() {
            return changedCount + leftOnlyCount + rightOnlyCount;
        }
    }

    private DataCompareService() {
    }

    public static DataDiff compare(QueryResult left, QueryResult right, List<String> keyColumns) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        List<String> keys = keyColumns == null || keyColumns.isEmpty()
                ? guessKeys(left, right)
                : List.copyOf(keyColumns);

        List<String> columns = mergeColumns(left.columnNames(), right.columnNames());
        Map<String, List<String>> leftMap = indexRows(left, keys, columns);
        Map<String, List<String>> rightMap = indexRows(right, keys, columns);

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(leftMap.keySet());
        allKeys.addAll(rightMap.keySet());

        List<RowDiff> diffs = new ArrayList<>();
        int match = 0;
        int changed = 0;
        int leftOnly = 0;
        int rightOnly = 0;

        for (String key : allKeys) {
            List<String> lv = leftMap.get(key);
            List<String> rv = rightMap.get(key);
            if (lv == null) {
                rightOnly++;
                diffs.add(new RowDiff(RowStatus.RIGHT_ONLY, key, null, rv, List.of()));
            } else if (rv == null) {
                leftOnly++;
                diffs.add(new RowDiff(RowStatus.LEFT_ONLY, key, lv, null, List.of()));
            } else {
                List<String> changedCols = new ArrayList<>();
                for (int i = 0; i < columns.size(); i++) {
                    String a = i < lv.size() ? lv.get(i) : null;
                    String b = i < rv.size() ? rv.get(i) : null;
                    if (!Objects.equals(a, b)) {
                        changedCols.add(columns.get(i));
                    }
                }
                if (changedCols.isEmpty()) {
                    match++;
                    diffs.add(new RowDiff(RowStatus.MATCH, key, lv, rv, List.of()));
                } else {
                    changed++;
                    diffs.add(new RowDiff(RowStatus.CHANGED, key, lv, rv, List.copyOf(changedCols)));
                }
            }
        }

        return new DataDiff(columns, keys, List.copyOf(diffs), match, changed, leftOnly, rightOnly);
    }

    private static List<String> guessKeys(QueryResult left, QueryResult right) {
        if (!left.columnNames().isEmpty() && right.columnNames().contains(left.columnNames().getFirst())) {
            return List.of(left.columnNames().getFirst());
        }
        if (!left.columnNames().isEmpty()) {
            return List.of(left.columnNames().getFirst());
        }
        return List.of();
    }

    private static List<String> mergeColumns(List<String> left, List<String> right) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.addAll(left);
        set.addAll(right);
        return List.copyOf(set);
    }

    private static Map<String, List<String>> indexRows(
            QueryResult result, List<String> keys, List<String> columns) {
        Map<String, Integer> indexByName = new LinkedHashMap<>();
        for (int i = 0; i < result.columnNames().size(); i++) {
            indexByName.put(result.columnNames().get(i).toLowerCase(Locale.ROOT), i);
        }
        List<Integer> keyIndexes = new ArrayList<>();
        for (String key : keys) {
            Integer idx = indexByName.get(key.toLowerCase(Locale.ROOT));
            if (idx != null) {
                keyIndexes.add(idx);
            }
        }
        Map<String, List<String>> map = new LinkedHashMap<>();
        int rowNum = 0;
        for (List<String> row : result.rows()) {
            String key = keyIndexes.isEmpty()
                    ? "#" + (rowNum++)
                    : buildKey(row, keyIndexes);
            List<String> aligned = new ArrayList<>(columns.size());
            for (String col : columns) {
                Integer idx = indexByName.get(col.toLowerCase(Locale.ROOT));
                aligned.add(idx == null || idx >= row.size() ? null : row.get(idx));
            }
            map.putIfAbsent(key, List.copyOf(aligned));
        }
        return map;
    }

    private static String buildKey(List<String> row, List<Integer> keyIndexes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyIndexes.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            int idx = keyIndexes.get(i);
            sb.append(idx < row.size() ? Objects.toString(row.get(idx), "\u0000") : "\u0000");
        }
        return sb.toString();
    }
}
