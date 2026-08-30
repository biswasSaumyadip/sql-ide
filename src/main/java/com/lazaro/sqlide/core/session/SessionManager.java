package com.lazaro.sqlide.core.session;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import com.lazaro.sqlide.core.db.DriverRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds multiple live {@link ConnectionSession}s. Connecting to a saved profile
 * reuses that profile's session; otherwise a new ephemeral session is created.
 */
public final class SessionManager implements AutoCloseable {

    private final DriverRegistry registry;
    private final Map<String, ConnectionSession> byId = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private String focusedId;

    public SessionManager(DriverRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Opens or reconnects a session. When {@code profileId} is set and a session
     * already exists for that profile, that driver is reused (reconnected).
     */
    public ConnectionSession open(
            String profileId,
            String displayName,
            ConnectionConfig config,
            int maxRows) {
        Objects.requireNonNull(config, "config");
        String name = displayName == null || displayName.isBlank()
                ? config.displayLabel()
                : displayName.strip();

        ConnectionSession existing = profileId == null || profileId.isBlank()
                ? null
                : findByProfileId(profileId).orElse(null);

        if (existing != null) {
            existing.updateConfig(config);
            existing.driver().setMaxRowsPerQuery(maxRows);
            focusedId = existing.id();
            fireChanged();
            return existing;
        }

        DataSourceDriver driver = registry.create(DriverRegistry.DEFAULT_DRIVER_ID);
        driver.setMaxRowsPerQuery(maxRows);
        String id = UUID.randomUUID().toString();
        ConnectionSession session = new ConnectionSession(id, profileId, name, driver, config);
        byId.put(id, session);
        focusedId = id;
        fireChanged();
        return session;
    }

    public Optional<ConnectionSession> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(sessionId));
    }

    public Optional<ConnectionSession> findByProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }
        for (ConnectionSession session : byId.values()) {
            if (session.profileId().filter(profileId::equals).isPresent()) {
                return Optional.of(session);
            }
        }
        return Optional.empty();
    }

    public List<ConnectionSession> sessions() {
        return List.copyOf(byId.values());
    }

    public List<ConnectionSession> connectedSessions() {
        List<ConnectionSession> connected = new ArrayList<>();
        for (ConnectionSession session : byId.values()) {
            if (session.isConnected()) {
                connected.add(session);
            }
        }
        return List.copyOf(connected);
    }

    public Optional<ConnectionSession> focused() {
        if (focusedId != null && byId.containsKey(focusedId)) {
            return Optional.of(byId.get(focusedId));
        }
        return connectedSessions().stream().findFirst();
    }

    public void focus(String sessionId) {
        if (sessionId != null && byId.containsKey(sessionId)) {
            focusedId = sessionId;
            fireChanged();
        }
    }

    public void focusSession(ConnectionSession session) {
        if (session != null) {
            focus(session.id());
        }
    }

    /** Closes and removes one session. Focus moves to another live session if any. */
    public void closeSession(String sessionId) {
        ConnectionSession removed = byId.remove(sessionId);
        if (removed == null) {
            return;
        }
        removed.close();
        if (Objects.equals(focusedId, sessionId)) {
            focusedId = byId.keySet().stream().findFirst().orElse(null);
        }
        fireChanged();
    }

    public void closeAll() {
        List<ConnectionSession> all = new ArrayList<>(byId.values());
        byId.clear();
        focusedId = null;
        for (ConnectionSession session : all) {
            session.close();
        }
        fireChanged();
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Convenience: apply {@code action} to the focused connected session, if any. */
    public void ifFocusedConnected(Consumer<ConnectionSession> action) {
        focused().filter(ConnectionSession::isConnected).ifPresent(action);
    }

    private void fireChanged() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // listeners must not break the manager
            }
        }
    }

    @Override
    public void close() {
        closeAll();
        listeners.clear();
    }
}
