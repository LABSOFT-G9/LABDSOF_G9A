package pt.isep.bmc.dispatch.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for the calendar simulator in Dispatch's integration tests,
 * with the same contract (technical design 4.5): bookings in memory, one per
 * request ID, and a queue of scripted outcomes per operation. The real
 * simulator is {@code services/calendar-sim}.
 */
public final class CalendarStub {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static CalendarStub shared;

    private final HttpServer server;
    private final Map<String, Map<String, Object>> bookings = new LinkedHashMap<>();
    private final Map<String, Deque<String>> scripts = new LinkedHashMap<>();
    private final List<Map<String, Object>> received = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private final AtomicLong references = new AtomicLong(50_000);

    private CalendarStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/bookings", this::handle);
        server.start();
        for (String operation : List.of("create", "query", "cancel")) {
            scripts.put(operation, new ArrayDeque<>());
        }
    }

    public static synchronized CalendarStub shared() {
        if (shared == null) {
            try {
                shared = new CalendarStub();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return shared;
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public synchronized void reset() {
        bookings.clear();
        scripts.values().forEach(Deque::clear);
        received.clear();
        requests.clear();
    }

    /**
     * Queues outcomes: {@code ok}, {@code conflict}, {@code unavailable},
     * {@code invalid} (a create's 2xx without a reference; a cancellation's
     * 422), {@code timeout}, {@code timeout_after_create}, and for a lookup
     * {@code wrong_window} (the booking, an hour later than booked).
     */
    public synchronized void script(String operation, String... outcomes) {
        scripts.get(operation).addAll(List.of(outcomes));
    }

    /** Loses every booking, as a simulator restart does. */
    public synchronized void loseAll() {
        bookings.clear();
    }

    /** The bodies of the create requests, with the idempotency key as {@code key}. */
    public synchronized List<Map<String, Object>> received() {
        return List.copyOf(received);
    }

    /** Every request as {@code METHOD path}. */
    public synchronized List<String> requests() {
        return List.copyOf(requests);
    }

    public synchronized int bookingCount() {
        return bookings.size();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        synchronized (this) {
            requests.add(method + " " + path);
        }
        if ("POST".equals(method) && path.equals("/bookings")) {
            create(exchange);
        } else if ("GET".equals(method) && path.equals("/bookings")) {
            find(exchange);
        } else if ("POST".equals(method) && path.endsWith("/cancellation")) {
            cancel(exchange, path.substring("/bookings/".length(), path.length() - "/cancellation".length()));
        } else {
            answer(exchange, 404, Map.of("code", "NOT_FOUND"));
        }
    }

    @SuppressWarnings("unchecked")
    private void create(HttpExchange exchange) throws IOException {
        Map<String, Object> body = JSON.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String outcome;
        Map<String, Object> booking;
        synchronized (this) {
            Map<String, Object> copy = new LinkedHashMap<>(body);
            copy.put("key", key);
            received.add(copy);
            outcome = next("create");
            booking = bookings.get((String) body.get("requestId"));
            if (booking == null && (outcome.equals("ok") || outcome.equals("timeout_after_create"))) {
                booking = new LinkedHashMap<>();
                booking.put("requestId", body.get("requestId"));
                booking.put("externalRef", "SIM-BK-" + references.incrementAndGet());
                booking.put("status", "confirmed");
                booking.put("window", body.get("window"));
                bookings.put((String) body.get("requestId"), booking);
            }
        }
        switch (outcome) {
            case "conflict" -> answer(exchange, 409, Map.of("code", "SLOT_CONFLICT"));
            case "unavailable" -> answer(exchange, 503, Map.of("code", "UNAVAILABLE"));
            case "invalid" -> answer(exchange, 201, Map.of("requestId", body.get("requestId"), "status", "confirmed"));
            case "timeout", "timeout_after_create" -> hang(exchange);
            default -> answer(exchange, 201, booking);
        }
    }

    private void find(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String requestId = URLDecoder.decode(query.substring(query.indexOf('=') + 1), StandardCharsets.UTF_8);
        String outcome;
        Map<String, Object> booking;
        synchronized (this) {
            outcome = next("query");
            booking = bookings.get(requestId);
        }
        switch (outcome) {
            case "unavailable" -> answer(exchange, 503, Map.of("code", "UNAVAILABLE"));
            case "timeout" -> hang(exchange);
            case "wrong_window" -> answer(exchange, 200, Map.of("items", booking == null ? List.of() : List.of(moved(booking))));
            default -> answer(exchange, 200, Map.of("items", booking == null ? List.of() : List.of(booking)));
        }
    }

    private void cancel(HttpExchange exchange, String externalRef) throws IOException {
        String outcome;
        Map<String, Object> found;
        synchronized (this) {
            outcome = next("cancel");
            found = bookings.values().stream().filter(b -> externalRef.equals(b.get("externalRef"))).findFirst()
                    .orElse(null);
            if (found != null && outcome.equals("ok")) {
                found.put("status", "cancelled");
            }
        }
        switch (outcome) {
            case "unavailable" -> answer(exchange, 503, Map.of("code", "UNAVAILABLE"));
            case "timeout" -> hang(exchange);
            case "invalid" -> answer(exchange, 422, Map.of("code", "INVALID_REQUEST"));
            default -> {
                if (found == null) {
                    answer(exchange, 404, Map.of("code", "NOT_FOUND"));
                } else {
                    answer(exchange, 200, found);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> moved(Map<String, Object> booking) {
        Map<String, Object> copy = new LinkedHashMap<>(booking);
        Map<String, Object> window = (Map<String, Object>) booking.get("window");
        copy.put("window", Map.of(
                "start", java.time.Instant.parse((String) window.get("start")).plusSeconds(3600).toString(),
                "end", java.time.Instant.parse((String) window.get("end")).plusSeconds(3600).toString()));
        return copy;
    }

    private String next(String operation) {
        String scripted = scripts.get(operation).poll();
        return scripted == null ? "ok" : scripted;
    }

    /** Longer than the 1-second timeout the tests give Dispatch. */
    private static void hang(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(2_500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        answer(exchange, 504, Map.of());
    }

    private static void answer(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            // The client gave up (timeout): nothing to answer.
        } finally {
            exchange.close();
        }
    }
}
