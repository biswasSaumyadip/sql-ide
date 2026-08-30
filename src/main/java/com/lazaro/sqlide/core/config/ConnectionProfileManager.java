package com.lazaro.sqlide.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists connection profiles (never passwords) under
 * {@code ~/.sql-ide-config/connections.json}.
 */
public final class ConnectionProfileManager {

    private static final Logger LOG = Logger.getLogger(ConnectionProfileManager.class.getName());

    private static final Path DEFAULT_FILE = Path.of(
            System.getProperty("user.home"), ".sql-ide-config", "connections.json");

    private final Path file;
    private final ObjectMapper mapper;

    public ConnectionProfileManager() {
        this(DEFAULT_FILE);
    }

    public ConnectionProfileManager(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path storageFile() {
        return file;
    }

    /**
     * Reads saved profiles. Creates the config folder and an empty JSON array when
     * missing. Returns an empty list on I/O or parse failure (never throws to the UI).
     */
    public List<ConnectionProfile> loadProfiles() {
        try {
            ensureStorageExists();
            if (Files.size(file) == 0) {
                writeAll(List.of());
                return List.of();
            }
            List<ConnectionProfile> loaded = mapper.readValue(file.toFile(), new TypeReference<>() {
            });
            if (loaded == null) {
                return List.of();
            }
            List<ConnectionProfile> sanitized = new ArrayList<>(loaded.size());
            for (ConnectionProfile profile : loaded) {
                if (profile != null && !profile.host().isBlank()) {
                    sanitized.add(profile);
                }
            }
            return List.copyOf(sanitized);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to load connection profiles from " + file, ex);
            return List.of();
        }
    }

    /**
     * Appends {@code profile} or replaces an existing one with the same id.
     * Failures are logged; the UI is not interrupted.
     */
    public void saveProfile(ConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        try {
            List<ConnectionProfile> profiles = new ArrayList<>(loadProfilesUnlocked());
            boolean replaced = false;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).id().equals(profile.id())) {
                    profiles.set(i, profile);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                profiles.add(profile);
            }
            writeAll(profiles);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to save connection profile '" + profile.name() + "'", ex);
        }
    }

    /** Removes a profile by id. No-op when missing. */
    public void deleteProfile(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            List<ConnectionProfile> profiles = new ArrayList<>(loadProfilesUnlocked());
            boolean removed = profiles.removeIf(profile -> id.equals(profile.id()));
            if (removed) {
                writeAll(profiles);
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to delete connection profile " + id, ex);
        }
    }

    private List<ConnectionProfile> loadProfilesUnlocked() throws IOException {
        ensureStorageExists();
        if (Files.size(file) == 0) {
            return new ArrayList<>();
        }
        List<ConnectionProfile> loaded = mapper.readValue(file.toFile(), new TypeReference<>() {
        });
        return loaded == null ? new ArrayList<>() : new ArrayList<>(loaded);
    }

    private void ensureStorageExists() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(file)) {
            writeAll(List.of());
        }
    }

    private void writeAll(List<ConnectionProfile> profiles) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), profiles);
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
