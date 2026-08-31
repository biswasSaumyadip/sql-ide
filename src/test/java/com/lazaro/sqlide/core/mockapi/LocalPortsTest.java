package com.lazaro.sqlide.core.mockapi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPortsTest {

    @Test
    void skipsAnOccupiedPort() throws IOException {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            int used = occupied.getLocalPort();
            int found = LocalPorts.findAvailable(used);
            assertTrue(found >= used);
            assertNotEquals(used, found);
        }
    }
}
