package com.lazaro.sqlide.core.redis;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.ScriptResult;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.util.SafeEncoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs Redis CLI-style lines through Jedis {@code sendCommand}, without a
 * per-command method switch.
 */
public final class RedisExecutor {

    private final RedisConnectionManager connections;

    public RedisExecutor(RedisConnectionManager connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public QueryResult execute(String line) {
        try (Jedis jedis = connections.borrow()) {
            return executeOn(jedis, line);
        } catch (RuntimeException e) {
            return QueryResult.ofError(rootMessage(e), 0L);
        }
    }

    public ScriptResult executeScript(List<String> lines) {
        List<String> cleaned = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                if (line != null && !line.isBlank()) {
                    cleaned.add(line);
                }
            }
        }
        if (cleaned.isEmpty()) {
            return new ScriptResult(List.of(QueryResult.ofError(
                    "Nothing to execute: the script is empty.", 0L)), 0L, true);
        }
        long startNanos = System.nanoTime();
        List<QueryResult> results = new ArrayList<>();
        try (Jedis jedis = connections.borrow()) {
            for (String line : cleaned) {
                QueryResult result = executeOn(jedis, line);
                results.add(result);
                if (result.isError()) {
                    return new ScriptResult(results, elapsedMs(startNanos), true);
                }
            }
            return new ScriptResult(results, elapsedMs(startNanos), false);
        } catch (RuntimeException e) {
            results.add(QueryResult.ofError(rootMessage(e), elapsedMs(startNanos)));
            return new ScriptResult(results, elapsedMs(startNanos), true);
        }
    }

    QueryResult executeOn(Jedis jedis, String line) {
        long startNanos = System.nanoTime();
        Optional<RedisCommandParser.ParsedCommand> parsed = RedisCommandParser.parse(line);
        if (parsed.isEmpty()) {
            return QueryResult.ofError("Nothing to execute: the command is empty.", elapsedMs(startNanos));
        }
        RedisCommandParser.ParsedCommand command = parsed.get();
        try {
            ProtocolCommand proto = rawCommand(command.command());
            Object raw = command.arguments().isEmpty()
                    ? jedis.sendCommand(proto)
                    : jedis.sendCommand(proto, command.argumentArray());
            Object decoded = SafeEncoder.encodeObject(raw);
            return RedisResultMapper.toQueryResult(decoded, command.command(), elapsedMs(startNanos));
        } catch (JedisException e) {
            return QueryResult.ofError(rootMessage(e), elapsedMs(startNanos));
        }
    }

    private static ProtocolCommand rawCommand(String name) {
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        return () -> raw;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
