package com.lazaro.sqlide.core.mockapi;

import com.lazaro.sqlide.core.db.QueryResult;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Embedded loopback JSON API backed by the current result grid.
 *
 * <p>One instance per editor/result tab. Call {@link #stop()} to release the port.
 */
public final class MockApiServer {

    public static final String PATH = "/api/mock";
    public static final int DEFAULT_PORT = LocalPorts.DEFAULT_START;

    private final Supplier<QueryResult> data;
    private final IntSupplier latencyMs;
    private final java.util.function.Consumer<String> accessLog;

    private HttpServer http;
    private ExecutorService executor;
    private int port;
    private volatile boolean running;

    public MockApiServer(
            Supplier<QueryResult> data,
            IntSupplier latencyMs,
            java.util.function.Consumer<String> accessLog) {
        this.data = Objects.requireNonNull(data, "data");
        this.latencyMs = latencyMs == null ? () -> 0 : latencyMs;
        this.accessLog = accessLog == null ? line -> { } : accessLog;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        http = bindFrom(DEFAULT_PORT);
        port = http.getAddress().getPort();
        executor = Executors.newCachedThreadPool(new DaemonFactory());
        http.setExecutor(executor);
        http.createContext(PATH, this::handle);
        http.start();
        running = true;
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return port;
    }

    public String url() {
        return "http://localhost:" + port + PATH;
    }

    /**
     * Binds {@link HttpServer} on the first free loopback port at or above
     * {@code startPort}.
     */
    static HttpServer bindFrom(int startPort) throws IOException {
        int probe = Math.max(1, startPort);
        int end = Math.min(65_535, probe + 255);
        IOException last = null;
        while (probe <= end) {
            int candidate = LocalPorts.findAvailable(probe);
            try {
                return HttpServer.create(new InetSocketAddress("127.0.0.1", candidate), 0);
            } catch (IOException ex) {
                last = ex;
                probe = candidate + 1;
            }
        }
        throw last != null ? last : new IOException("No free loopback port from " + startPort);
    }

    private void handle(HttpExchange exchange) throws IOException {
        applyCors(exchange.getResponseHeaders());
        String method = exchange.getRequestMethod() == null ? "" : exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();
        if (!PATH.equals(path) && !(PATH + "/").equals(path)) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found");
            log(404, method, exchange);
            return;
        }
        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            log(204, method, exchange);
            return;
        }
        if (!"GET".equals(method)) {
            send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
            log(405, method, exchange);
            return;
        }

        sleepLatency();

        QueryResult result;
        try {
            result = data.get();
        } catch (RuntimeException ex) {
            send(exchange, 500, "text/plain; charset=utf-8",
                    ex.getMessage() == null ? "Failed to read result set" : ex.getMessage());
            log(500, method, exchange);
            return;
        }
        if (result == null || result.isError() || !result.isResultSet()) {
            send(exchange, 404, "text/plain; charset=utf-8", "No result set is loaded");
            log(404, method, exchange);
            return;
        }

        List<Map<String, Object>> rows = MockApiJson.toRowMaps(result);
        MockApiPagination.Slice slice;
        try {
            slice = MockApiPagination.parse(exchange.getRequestURI().getRawQuery(), rows.size());
        } catch (IllegalArgumentException | ArithmeticException ex) {
            send(exchange, 400, "text/plain; charset=utf-8", ex.getMessage());
            log(400, method, exchange);
            return;
        }
        List<Map<String, Object>> page = slice.apply(rows);
        exchange.getResponseHeaders().set("X-Total-Count", Integer.toString(slice.total()));
        exchange.getResponseHeaders().set("X-Page", Integer.toString(slice.page()));
        exchange.getResponseHeaders().set("X-Limit", Integer.toString(slice.limit()));
        send(exchange, 200, "application/json; charset=utf-8", MockApiJson.toJsonArray(page));
        log(200, method, exchange);
    }

    private void sleepLatency() {
        int delay = Math.max(0, latencyMs.getAsInt());
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(int status, String method, HttpExchange exchange) {
        String uri = exchange.getRequestURI().toString();
        String phrase = switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Error";
            default -> Integer.toString(status);
        };
        accessLog.accept("[" + status + " " + phrase + "] " + method + " " + uri);
    }

    private static void applyCors(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "*");
        headers.set("Access-Control-Expose-Headers", "X-Total-Count, X-Page, X-Limit");
        headers.set("Access-Control-Max-Age", "86400");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        applyCors(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class DaemonFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mock-api-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
