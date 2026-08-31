package com.lazaro.sqlide.core.mockapi;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketException;

/**
 * Finds the first free TCP port on loopback, scanning upward from a start port
 * so the mock API never hard-codes {@code 8080}.
 */
public final class LocalPorts {

    static final int DEFAULT_START = 8080;
    private static final int MAX_ATTEMPTS = 256;

    private LocalPorts() {
    }

    /** First open loopback port at or above {@code 8080}. */
    public static int findAvailable() throws IOException {
        return findAvailable(DEFAULT_START);
    }

    /**
     * First open loopback port at or above {@code startInclusive}.
     *
     * @throws IOException if no port in the scan window can be bound
     */
    public static int findAvailable(int startInclusive) throws IOException {
        int start = Math.clamp(startInclusive, 1, 65_535);
        int end = Math.min(65_535, start + MAX_ATTEMPTS - 1);
        for (int port = start; port <= end; port++) {
            if (isFree(port)) {
                return port;
            }
        }
        throw new IOException("No free loopback port in " + start + "\u2013" + end);
    }

    static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (SocketException ex) {
            return false;
        } catch (IOException ex) {
            return false;
        }
    }
}
