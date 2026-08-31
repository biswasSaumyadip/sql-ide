package com.lazaro.sqlide.core.mockapi;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.ResultColumn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockApiServerTest {

    private MockApiServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void servesCorsPaginatedJson() throws Exception {
        QueryResult result = QueryResult.ofRows(
                List.of("id", "name"),
                List.of(List.of("1", "Ada"), List.of("2", "Grace"), List.of("3", "Edsger")),
                1L,
                false,
                List.of(
                        new ResultColumn("id", "INT", Types.INTEGER, true, false),
                        new ResultColumn("name", "VARCHAR", Types.VARCHAR, false, false)));
        List<String> log = new CopyOnWriteArrayList<>();
        server = new MockApiServer(() -> result, () -> 0, log::add);
        server.start();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String base = "http://127.0.0.1:" + server.port() + MockApiServer.PATH;
        HttpResponse<String> all = get(client, base);
        assertEquals(200, all.statusCode());
        assertEquals("*", all.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertTrue(all.body().contains("\"Ada\""));
        assertTrue(all.body().contains("\"id\":1"));

        HttpResponse<String> page = get(client, base + "?page=2&limit=1");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Grace"));
        assertTrue(!page.body().contains("Ada"));
        assertEquals("3", page.headers().firstValue("X-Total-Count").orElse(""));

        HttpRequest options = HttpRequest.newBuilder(URI.create(base))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(2))
                .build();
        HttpResponse<String> preflight = client.send(options, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals("*", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

        assertTrue(log.stream().anyMatch(line -> line.contains("[200 OK] GET")));
        assertTrue(log.stream().anyMatch(line -> line.contains("OPTIONS")));
    }

    @Test
    void stopReleasesThePort() throws Exception {
        QueryResult result = QueryResult.ofRows(
                List.of("id"),
                List.of(List.of("1")),
                1L,
                false,
                List.of(new ResultColumn("id", "INT", Types.INTEGER, false, false)));
        server = new MockApiServer(() -> result, () -> 0, line -> { });
        server.start();
        int port = server.port();
        server.stop();
        server = null;
        assertTrue(LocalPorts.isFree(port));
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(2))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
