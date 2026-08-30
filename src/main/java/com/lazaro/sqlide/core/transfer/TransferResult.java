package com.lazaro.sqlide.core.transfer;

import java.util.List;
import java.util.Objects;

/** Outcome of a completed (or cancelled) transfer. */
public record TransferResult(
        TransferRequest.Strategy strategy,
        long rowsTransferred,
        long rowsSkipped,
        long elapsedMs,
        boolean cancelled,
        String message,
        List<String> errorLog
) {
    public TransferResult {
        Objects.requireNonNull(strategy, "strategy");
        message = Objects.requireNonNullElse(message, "");
        errorLog = List.copyOf(Objects.requireNonNullElse(errorLog, List.of()));
    }

    public boolean succeeded() {
        return !cancelled && errorLog.isEmpty() || rowsTransferred > 0 && !cancelled;
    }
}
