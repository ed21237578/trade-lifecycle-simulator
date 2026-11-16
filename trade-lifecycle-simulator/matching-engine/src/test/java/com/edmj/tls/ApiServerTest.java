package com.edmj.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests: start the real HTTP server on a random port and call it. */
class ApiServerTest {
    private static final String KEY = "test-key";
    private ApiServer server;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = new ApiServer(new MatchingEngine(Set.of("AAPL")), KEY);
        server.start(0);
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> post(String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            b.header("X-API-Key", key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthCheckIsUp() throws Exception {
        HttpResponse<String> r = get("/health");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"status\":\"UP\""));
    }

    @Test
    void matchingOrdersProduceATrade() throws Exception {
        assertEquals(201, post("{\"traderId\":\"bob\",\"symbol\":\"AAPL\",\"side\":\"SELL\",\"pricePence\":15000,\"quantity\":10}", KEY).statusCode());
        HttpResponse<String> buy = post("{\"traderId\":\"alice\",\"symbol\":\"AAPL\",\"side\":\"BUY\",\"pricePence\":15000,\"quantity\":10}", KEY);
        assertEquals(201, buy.statusCode());
        assertTrue(buy.body().contains("\"filledQuantity\":10"));

        HttpResponse<String> trades = get("/api/trades?since=0");
        assertTrue(trades.body().contains("\"buyerId\":\"alice\""));
    }

    @Test
    void rejectsMissingApiKey() throws Exception {
        assertEquals(401, post("{}", null).statusCode());
        assertEquals(401, post("{}", "wrong").statusCode());
    }

    @Test
    void rejectsInvalidOrderWithReasons() throws Exception {
        HttpResponse<String> r = post("{\"traderId\":\"alice\",\"symbol\":\"AAPL\",\"side\":\"BUY\",\"pricePence\":15000,\"quantity\":-1}", KEY);
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("quantity"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        assertEquals(400, post("not json", KEY).statusCode());
    }

    @Test
    void cancelRestingOrder() throws Exception {
        post("{\"traderId\":\"bob\",\"symbol\":\"AAPL\",\"side\":\"SELL\",\"pricePence\":15000,\"quantity\":10}", KEY);
        HttpRequest del = HttpRequest.newBuilder(URI.create(base + "/api/orders/1"))
                .header("X-API-Key", KEY).DELETE().build();
        assertEquals(200, http.send(del, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, http.send(del, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void unknownSymbolBookIs404() throws Exception {
        assertEquals(404, get("/api/book?symbol=NOPE").statusCode());
    }
}
