package pt.isep.bmc.dispatch.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal HTTP client for the service under test, over a real socket. Like
 * the SPA, it sends the custom CSRF header on every request unless built with
 * {@link #withoutCsrfHeader()}.
 */
public final class Api {

    /** The service's default {@code bmc.security.csrf-header}. */
    public static final String CSRF_HEADER = "X-BMC-CSRF";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final boolean csrfHeader;

    public Api(int port) {
        this("http://localhost:" + port, true);
    }

    private Api(String base, boolean csrfHeader) {
        this.base = base;
        this.csrfHeader = csrfHeader;
    }

    /** The same client, but as a cross-site form would send requests. */
    public Api withoutCsrfHeader() {
        return new Api(base, false);
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

    /** A POST with extra headers, for example {@code If-Match}; a {@code null} value leaves the header out. */
    public Response postWithHeaders(String path, String token, Map<String, String> headers, Object body) {
        HttpRequest.Builder request = request(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body)));
        headers.forEach((name, value) -> {
            if (value != null) {
                request.header(name, value);
            }
        });
        return send(request.build());
    }

    /** Any method with a JSON body and extra headers; a {@code null} header value leaves the header out. */
    public Response send(String method, String path, String token, Map<String, String> headers, Object body) {
        HttpRequest.Builder request = request(path, token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(
                        body instanceof String s ? s : JSON.writeValueAsString(body)));
        headers.forEach((name, value) -> {
            if (value != null) {
                request.header(name, value);
            }
        });
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
        if (csrfHeader) {
            builder.header(CSRF_HEADER, "1");
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

    /** A JSON object nested in a response body. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /** A JSON array nested in a response body. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return (List<Object>) value;
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
