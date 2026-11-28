package pt.isep.bmc.calendarsim.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** A plain HTTP client for the simulator's API, as Dispatch's adapter would call it. */
final class SimClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;

    SimClient(int port) {
        this.base = "http://localhost:" + port;
    }

    record Response(int status, String body, String contentType) {

        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            return JSON.readValue(body, Map.class);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items() {
            return (List<Map<String, Object>>) json().get("items");
        }
    }

    static Map<String, Object> booking(String requestId, String contractorRef, String start, String end) {
        return Map.of("requestId", requestId, "contractorRef", contractorRef,
                "window", Map.of("start", start, "end", end), "buildingCode", "B-001",
                "summary", "Plumbing intervention");
    }

    Response create(String key, Object body) throws Exception {
        return create(key, body, Duration.ofSeconds(30));
    }

    Response create(String key, Object body, Duration timeout) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/bookings"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return send(request.build());
    }

    /** A booking request with a body as given, which need not be valid JSON. */
    Response createRaw(String key, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + "/bookings"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    Response scriptRaw(String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + "/control/outcomes"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    /** {@code GET /bookings} accepting only {@code application/problem+json}, as a strict client might. */
    Response findAcceptingProblems(String requestId) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + "/bookings?requestId=" + requestId))
                .header("Accept", "application/problem+json").GET().build());
    }

    Response find(String requestId) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + "/bookings?requestId=" + requestId)).GET().build());
    }

    Response cancel(String externalRef, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/bookings/" + externalRef + "/cancellation"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return send(request.build());
    }

    Response script(String operation, List<Map<String, Object>> outcomes) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + "/control/outcomes"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(Map.of("operation", operation, "outcomes", outcomes))))
                .build());
    }

    Response control(String method, String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + "/control" + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build());
    }

    Response get(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).GET().build());
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("Content-Type").orElse(""));
    }
}
