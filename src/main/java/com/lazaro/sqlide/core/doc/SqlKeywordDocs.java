package com.lazaro.sqlide.core.doc;

import java.util.Locale;
import java.util.Optional;

/**
 * Short hover / completion text for SQL keywords that are easy to confuse
 * (routines and the MySQL client {@code DELIMITER} command).
 */
public final class SqlKeywordDocs {

    private SqlKeywordDocs() {
    }

    public static Optional<String> describe(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Optional.empty();
        }
        return switch (keyword.toUpperCase(Locale.ROOT)) {
            case "PROCEDURE" -> Optional.of(
                    "Stored procedure. Define with CREATE PROCEDURE; run with CALL name().");
            case "FUNCTION" -> Optional.of(
                    "Stored function. Define with CREATE FUNCTION; call it as an expression in SQL.");
            case "TRIGGER" -> Optional.of(
                    "Trigger. CREATE TRIGGER runs SQL automatically before or after a table event.");
            case "DELIMITER" -> Optional.of(
                    "Client command — not sent to the server. Changes the statement terminator "
                            + "so procedure bodies can contain semicolons.\n"
                            + "Example:\n"
                            + "DELIMITER $$\n"
                            + "CREATE PROCEDURE name()\n"
                            + "BEGIN\n"
                            + "    -- statements;\n"
                            + "END$$\n"
                            + "DELIMITER ;");
            case "CALL" -> Optional.of(
                    "CALL name() — execute a stored procedure.");
            default -> Optional.empty();
        };
    }
}
