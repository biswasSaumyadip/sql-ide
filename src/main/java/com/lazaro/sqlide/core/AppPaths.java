package com.lazaro.sqlide.core;

import java.nio.file.Path;

/** Resolves the on-disk app data directory ({@code ~/.sql-ide}). */
public final class AppPaths {

    private static final String DIR_NAME = ".sql-ide";

    private AppPaths() {
    }

    public static Path dataDirectory() {
        return Path.of(System.getProperty("user.home"), DIR_NAME);
    }

    public static Path historyFile() {
        return dataDirectory().resolve("query-history.json");
    }

    public static Path snippetsFile() {
        return dataDirectory().resolve("snippets.json");
    }
}
