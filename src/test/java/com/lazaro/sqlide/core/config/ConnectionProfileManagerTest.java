package com.lazaro.sqlide.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionProfileManagerTest {

    @TempDir
    Path tempDir;

    private ConnectionProfileManager manager;
    private Path file;

    @BeforeEach
    void setUp() {
        file = tempDir.resolve("connections.json");
        manager = new ConnectionProfileManager(file);
    }

    @Test
    void loadCreatesEmptyFileWhenMissing() throws Exception {
        List<ConnectionProfile> profiles = manager.loadProfiles();
        assertTrue(profiles.isEmpty());
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("["));
    }

    @Test
    void saveAppendsAndUpdatesById() {
        String id = UUID.randomUUID().toString();
        manager.saveProfile(new ConnectionProfile(id, "Local MySQL", "MYSQL", "localhost", 3306, "app", "root"));
        manager.saveProfile(new ConnectionProfile(
                UUID.randomUUID().toString(), "Other", "POSTGRESQL", "db", 5432, "sales", "admin"));

        List<ConnectionProfile> loaded = manager.loadProfiles();
        assertEquals(2, loaded.size());

        manager.saveProfile(new ConnectionProfile(id, "Local MySQL (renamed)", "MYSQL", "127.0.0.1", 3307, "app", "root"));
        loaded = manager.loadProfiles();
        assertEquals(2, loaded.size());
        ConnectionProfile updated = loaded.stream().filter(p -> id.equals(p.id())).findFirst().orElseThrow();
        assertEquals("Local MySQL (renamed)", updated.name());
        assertEquals("127.0.0.1", updated.host());
        assertEquals(3307, updated.port());
    }

    @Test
    void persistedJsonNeverContainsPassword() throws Exception {
        manager.saveProfile(new ConnectionProfile(
                UUID.randomUUID().toString(), "Secure", "MYSQL", "localhost", 3306, "db", "user"));
        String json = Files.readString(file).toLowerCase();
        assertFalse(json.contains("password"));
        assertFalse(json.contains("secret"));
    }

    @Test
    void roundTripsEnvironmentTunnelAndJdbcProperties() {
        String id = UUID.randomUUID().toString();
        manager.saveProfile(new ConnectionProfile(
                id,
                "Prod",
                "MYSQL",
                "db.internal",
                3306,
                "app",
                "root",
                "PRODUCTION",
                new com.lazaro.sqlide.core.db.ConnectionConfig.TunnelSettings(
                        true, "bastion", 22, "ubuntu", "/home/ubuntu/.ssh/id_rsa",
                        true, "/etc/ssl/ca.pem", ""),
                Map.of("useSSL", "true")));

        ConnectionProfile loaded = manager.loadProfiles().stream()
                .filter(profile -> id.equals(profile.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("PRODUCTION", loaded.environment());
        assertTrue(loaded.tunnel().sshEnabled());
        assertEquals("bastion", loaded.tunnel().sshHost());
        assertEquals("true", loaded.jdbcProperties().get("useSSL"));
    }

    @Test
    void loadsLegacyJsonWithoutNewFields() throws Exception {
        Files.writeString(file, """
                [{"id":"legacy","name":"Legacy","driver":"MYSQL","host":"localhost","port":3306,"database":"app","username":"root"}]
                """);
        List<ConnectionProfile> loaded = manager.loadProfiles();
        assertEquals(1, loaded.size());
        assertEquals("Legacy", loaded.getFirst().name());
        assertEquals("NONE", loaded.getFirst().environment());
        assertTrue(loaded.getFirst().jdbcProperties().isEmpty());
        assertFalse(loaded.getFirst().tunnel().sshEnabled());
    }

    @Test
    void deleteRemovesProfile() {
        ConnectionProfile profile = new ConnectionProfile(
                UUID.randomUUID().toString(), "Temp", "MYSQL", "localhost", 3306, "", "root");
        manager.saveProfile(profile);
        assertEquals(1, manager.loadProfiles().size());
        manager.deleteProfile(profile.id());
        assertTrue(manager.loadProfiles().isEmpty());
    }
}
