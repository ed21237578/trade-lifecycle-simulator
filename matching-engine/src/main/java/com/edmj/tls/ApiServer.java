package com.edmj.tls;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * REST API in front of the matching engine, built on the JDK's own HTTP server
 * so the service has almost no dependencies.
 *
 *   POST   /api/orders          submit an order (needs X-API-Key if a key is set)
 *   DELETE /api/orders/{id}     cancel a resting order (needs X-API-Key)
 *   GET    /api/book?symbol=X   order book depth for one symbol
 *   GET    /api/trades?since=N  trades with id greater than N
 *   GET    /api/symbols         tradable symbols
 *   GET    /health              liveness check plus basic metrics
 *   GET    /                    the dashboard
 */
public final class ApiServer {
    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());
    private static final int MAX_BODY_BYTES = 4_096;

    private final MatchingEngine engine;
    private final OrderValidator validator;
    private final String apiKey;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final long startedAt = System.currentTimeMillis();
    private HttpServer server;

    public ApiServer(MatchingEngine engine, String apiKey) {
        this.engine = engine;
        this.validator = new OrderValidator(engine.symbols());
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/orders", this::handleOrders);
        server.createContext("/api/book", ex -> handleGet(ex, this::book));
        server.createContext("/api/trades", ex -> handleGet(ex, this::trades));
        server.createContext("/api/symbols", ex -> handleGet(ex, e -> ok(engine.symbols())));
        server.createContext("/health", ex -> handleGet(ex, e -> ok(health())));
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        LOG.info("Matching engine API listening on port " + port() + (apiKey == null ? " (no API key set)" : ""));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------- handlers ----------

    private void handleOrders(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                send(ex, 204, null);
                return;
            }
            if (!authorised(ex)) {
                send(ex, 401, error("Missing or invalid X-API-Key header"));
                return;
            }
            if ("POST".equals(method) && "/api/orders".equals(ex.getRequestURI().getPath())) {
                submitOrder(ex);
            } else if ("DELETE".equals(method)) {
                cancelOrder(ex);
            } else {
                send(ex, 405, error("Method not allowed"));
            }
        } catch (RuntimeException e) {
            LOG.severe("Unexpected error: " + e);
            send(ex, 500, error("Internal error"));
        }
    }

    private void submitOrder(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        if (body == null) {
            send(ex, 413, error("Request body too large"));
            return;
        }
        OrderValidator.OrderRequest req;
        try {
            req = gson.fromJson(body, OrderValidator.OrderRequest.class);
        } catch (JsonParseException e) {
            send(ex, 400, error("Body must be valid JSON"));
            return;
        }
        List<String> problems = validator.validate(req);
        if (!problems.isEmpty()) {
            send(ex, 400, Map.of("errors", problems));
            return;
        }
        MatchingEngine.SubmitResult result = engine.submit(
                req.traderId(), req.symbol(), req.side(), req.pricePence(), req.quantity());
        if (!result.trades().isEmpty()) {
            LOG.fine(() -> "Order " + result.orderId() + " produced " + result.trades().size() + " trade(s)");
        }
        send(ex, 201, result);
    }

    private void cancelOrder(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String prefix = "/api/orders/";
        String idPart = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
        long id;
        try {
            id = Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            send(ex, 400, error("Order id must be a number"));
            return;
        }
        if (engine.cancel(id)) {
            send(ex, 200, Map.of("cancelled", id));
        } else {
            send(ex, 404, error("Order " + id + " is not resting in the book"));
        }
    }

    private Response book(HttpExchange ex) {
        String symbol = query(ex.getRequestURI(), "symbol");
        Map<String, Object> snap = symbol == null ? null : engine.bookSnapshot(symbol);
        return snap == null ? new Response(404, error("Unknown symbol")) : ok(snap);
    }

    private Response trades(HttpExchange ex) {
        String since = query(ex.getRequestURI(), "since");
        long sinceId;
        try {
            sinceId = since == null ? 0 : Long.parseLong(since);
        } catch (NumberFormatException e) {
            return new Response(400, error("since must be a number"));
        }
        return ok(engine.tradesSince(sinceId));
    }

    private Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", "UP");
        h.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        h.putAll(engine.stats());
        return h;
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            send(ex, 404, error("Not found"));
            return;
        }
        try (InputStream in = ApiServer.class.getResourceAsStream("/static/index.html")) {
            if (in == null) {
                send(ex, 404, error("Dashboard not packaged"));
                return;
            }
            byte[] html = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(html);
            }
        }
    }

    // ---------- helpers ----------

    private interface Route {
        Response handle(HttpExchange ex);
    }

    private record Response(int status, Object body) {
    }

    private void handleGet(HttpExchange ex, Route route) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, error("Method not allowed"));
            return;
        }
        Response r = route.handle(ex);
        send(ex, r.status(), r.body());
    }

    private static Response ok(Object body) {
        return new Response(200, body);
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message);
    }

    /** Constant-time comparison so the key can't be guessed by timing how long a check takes. */
    private boolean authorised(HttpExchange ex) {
        if (apiKey == null) {
            return true;
        }
        String given = ex.getRequestHeaders().getFirst("X-API-Key");
        return given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads the body, or returns null if it is bigger than MAX_BODY_BYTES. */
    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            return bytes.length > MAX_BODY_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String query(URI uri, String key) {
        String q = uri.getQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return kv[1];
            }
        }
        return null;
    }

    private void send(HttpExchange ex, int status, Object body) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-API-Key");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        byte[] json = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, json.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(json);
        }
    }
}
