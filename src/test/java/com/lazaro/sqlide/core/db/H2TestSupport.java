package com.lazaro.sqlide.core.db;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared helpers for tests that run against a throwaway in-memory H2 database. */
final class H2TestSupport {

    static final long TIMEOUT_SECONDS = 20L;
    static final TimeUnit TIMEOUT_UNIT = TimeUnit.SECONDS;

    private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

    private H2TestSupport() {
    }

    /** A configuration pointing at a database name no other test uses. */
    static ConnectionConfig freshDatabase() {
        String name = "sqlide_test_" + DATABASE_COUNTER.incrementAndGet();
        return new ConnectionConfig("localhost", 9092, name, "sa", "", ConnectionConfig.Driver.H2_MEMORY);
    }
}
