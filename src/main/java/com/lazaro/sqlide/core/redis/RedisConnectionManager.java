package com.lazaro.sqlide.core.redis;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;

import java.util.Objects;
import java.util.Optional;

/**
 * Owns the {@link JedisPool} for one Redis endpoint. Opened when the user
 * connects, closed when they disconnect.
 */
public final class RedisConnectionManager implements AutoCloseable {

    static final int TIMEOUT_MS = 10_000;

    private volatile JedisPool pool;
    private volatile ConnectionConfig config;

    public void connect(ConnectionConfig newConfig) {
        Objects.requireNonNull(newConfig, "config must not be null");
        if (newConfig.connectionType() != ConnectionConfig.ConnectionType.REDIS) {
            throw new IllegalArgumentException("RedisConnectionManager requires a Redis connection, got "
                    + newConfig.driver().displayName());
        }
        close();
        JedisPool created = createPool(newConfig);
        try (Jedis jedis = created.getResource()) {
            jedis.ping();
        } catch (RuntimeException e) {
            created.close();
            throw e;
        }
        this.pool = created;
        this.config = newConfig;
    }

    /** Opens a throwaway pool, pings, reports a short server description, then closes. */
    public static String probe(ConnectionConfig candidate) {
        RedisConnectionManager manager = new RedisConnectionManager();
        try {
            manager.connect(candidate);
            return manager.serverDescription();
        } finally {
            manager.close();
        }
    }

    public Jedis borrow() {
        JedisPool current = pool;
        if (current == null || current.isClosed()) {
            throw new IllegalStateException("Not connected. Open a Redis connection before running commands.");
        }
        return current.getResource();
    }

    public boolean isConnected() {
        JedisPool current = pool;
        return current != null && !current.isClosed();
    }

    public Optional<ConnectionConfig> currentConfig() {
        return Optional.ofNullable(config);
    }

    public String serverDescription() {
        try (Jedis jedis = borrow()) {
            String info = jedis.info("server");
            if (info != null) {
                for (String line : info.split("\\R")) {
                    if (line.startsWith("redis_version:")) {
                        return "Redis " + line.substring("redis_version:".length()).trim();
                    }
                }
            }
            return "Redis";
        } catch (RuntimeException e) {
            return "Redis";
        }
    }

    @Override
    public void close() {
        JedisPool current = pool;
        pool = null;
        config = null;
        if (current != null && !current.isClosed()) {
            current.close();
        }
    }

    private static JedisPool createPool(ConnectionConfig config) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(TIMEOUT_MS)
                .socketTimeoutMillis(TIMEOUT_MS);
        if (!config.user().isBlank()) {
            builder.user(config.user());
        }
        if (!config.password().isBlank()) {
            builder.password(config.password());
        }
        JedisClientConfig clientConfig = builder.build();
        return new JedisPool(new HostAndPort(config.host(), config.port()), clientConfig);
    }
}
