package com.lazaro.sqlide.core.transfer;

/** Progress callbacks invoked from the transfer worker thread. */
@FunctionalInterface
public interface TransferProgressListener {

    void onProgress(long transferred, long total, String detail);

    default void onLog(String line) {
        // optional
    }
}
