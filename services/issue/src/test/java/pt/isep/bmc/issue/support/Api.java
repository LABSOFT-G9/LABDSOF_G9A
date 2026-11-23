package pt.isep.bmc.issue.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Minimal HTTP client for the service under test, over a real socket. */
public final class Api {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    public Api(int port) {
        this.base = "http://localhost:" + port;
    }

    public Response post(String path, String token, String idempotencyKey, Object body) {
        HttpRequest.Builder request = request(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body)));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return send(request.build());
    }

    public Response get(String path, String token) {
        return send(request(path, token).GET().build());
    }

    public Response method(String method, String path, String token, String body) {
        return send(request(path, token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private Response send(HttpRequest request) {
        try {
            return new Response(http.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Status, headers and the body parsed as a JSON object or list. */
    public record Response(HttpResponse<String> raw) {

        public int status() {
            return raw.statusCode();
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        public String body() {
            return raw.body();
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            return JSON.readValue(raw.body(), Map.class);
        }

        @SuppressWarnings("unchecked")
        public List<Object> jsonList() {
            return JSON.readValue(raw.body(), List.class);
        }
    }
}
