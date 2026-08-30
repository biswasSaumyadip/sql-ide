package com.lazaro.sqlide.core.db;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of running one or more statements in order. Stops at the first error
 * (prior successes are still included). Free of JDBC handles.
 *
 * @param results      one entry per statement that ran (including a trailing error)
 * @param totalTimeMs  wall-clock across the whole script
 * @param stoppedEarly {@code true} when an error halted remaining statements
 */
public record ScriptResult(List<QueryResult> results, long totalTimeMs, boolean stoppedEarly) {

    public ScriptResult {
        results = List.copyOf(Objects.requireNonNullElse(results, List.of()));
    }

    public static ScriptResult ofSingle(QueryResult result) {
        Objects.requireNonNull(result, "result");
        return new ScriptResult(List.of(result), result.executionTimeMs(), result.isError());
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }

    public int successCount() {
        int n = 0;
        for (QueryResult result : results) {
            if (!result.isError()) {
                n++;
            }
        }
        return n;
    }

    public int errorCount() {
        return results.size() - successCount();
    }

    public String summary() {
        return summary(false);
    }

    public String summary(boolean redis) {
        if (results.isEmpty()) {
            return "Nothing executed";
        }
        if (results.size() == 1) {
            return results.getFirst().summary(redis);
        }
        String unit = redis ? "commands" : "statements";
        String base = "%d %s \u2014 %d ok, %d failed (%d ms)".formatted(
                results.size(), unit, successCount(), errorCount(), totalTimeMs);
        return stoppedEarly ? base + ", stopped early" : base;
    }
}
