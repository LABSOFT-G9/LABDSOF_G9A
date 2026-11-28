package pt.isep.bmc.dispatch.adapters.out.calendar;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pt.isep.bmc.dispatch.application.port.out.CalendarPort;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.CalendarReply;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link CalendarPort} over the calendar simulator's HTTP API (ADR-006,
 * technical design 4.5). Every answer is classified, never thrown: a {@code
 * 2xx} counts as a confirmation only if it echoes the request ID, has an
 * external reference and returns the requested window; a timeout or any
 * other doubtful answer is {@code Unknown}, so the worker reconciles before
 * re-sending. Nothing of the request body is logged.
 */
@Component
class SimulatorCalendarAdapter implements CalendarPort {

    private static final Logger log = LoggerFactory.getLogger(SimulatorCalendarAdapter.class);

    private final CalendarProperties properties;
    private final JsonMapper json;
    private final HttpClient http;

    SimulatorCalendarAdapter(CalendarProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    @Override
    public CalendarReply create(BookingCall call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", call.id().toString());
        body.put("contractorRef", call.contractorRef());
        body.put("window", Map.of("start", time(call.window().start()), "end", time(call.window().end())));
        body.put("buildingCode", call.buildingCode());
        body.put("summary", call.summary());
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/bookings"))
                .timeout(properties.timeout())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", call.id().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return send(request, response -> switch (response.statusCode()) {
            case 200, 201 -> booking(response.body())
                    .filter(b -> b.matches(call.id(), Optional.of(call.window())) && b.confirmed())
                    .<CalendarReply>map(b -> new CalendarReply.Booked(b.externalRef()))
                    .orElse(new CalendarReply.Unknown());
            case 409 -> new CalendarReply.Conflict();
            case 422 -> defect("create", response);
            case 503 -> new CalendarReply.Unavailable();
            default -> new CalendarReply.Unknown();
        });
    }

    @Override
    public CalendarReply find(BookingId id, TimeWindow requested) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/bookings?requestId="
                        + URLEncoder.encode(id.toString(), StandardCharsets.UTF_8)))
                .timeout(properties.timeout())
                .GET()
                .build();
        return send(request, response -> switch (response.statusCode()) {
            case 200 -> items(response.body()).map(items -> {
                if (items.isEmpty()) {
                    return (CalendarReply) new CalendarReply.Absent();
                }
                Optional<Answer> found = items.size() == 1 ? answer(items.getFirst()) : Optional.empty();
                return found.filter(b -> b.matches(id, Optional.empty()))
                        .<CalendarReply>map(b -> {
                            if ("cancelled".equals(b.status())) {
                                return new CalendarReply.Cancelled();
                            }
                            if (!b.confirmed()) {
                                return new CalendarReply.Unknown();
                            }
                            // The same check as a create's answer: only the requested window is the booking.
                            return b.matches(id, Optional.of(requested))
                                    ? new CalendarReply.Booked(b.externalRef())
                                    : new CalendarReply.Mismatched(b.externalRef());
                        })
                        .orElse(new CalendarReply.Unknown());
            }).orElse(new CalendarReply.Unknown());
            case 503 -> new CalendarReply.Unavailable();
            default -> new CalendarReply.Unknown();
        });
    }

    @Override
    public CalendarReply cancel(String externalRef, BookingId id) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/bookings/"
                        + URLEncoder.encode(externalRef, StandardCharsets.UTF_8).replace("+", "%20") + "/cancellation"))
                .timeout(properties.timeout())
                .header("Idempotency-Key", "cancel-" + id)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request, response -> switch (response.statusCode()) {
            case 200 -> new CalendarReply.Cancelled();
            case 404 -> new CalendarReply.UnknownReference();
            case 422 -> defect("cancel", response);
            case 503 -> new CalendarReply.Unavailable();
            default -> new CalendarReply.Unknown();
        });
    }

    private interface Classifier {
        CalendarReply classify(HttpResponse<String> response);
    }

    private CalendarReply send(HttpRequest request, Classifier classifier) {
        try {
            return classifier.classify(http.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (HttpConnectTimeoutException e) {
            // No connection, so nothing was sent: retry, nothing to reconcile.
            return new CalendarReply.Unavailable();
        } catch (HttpTimeoutException e) {
            return new CalendarReply.Unknown();
        } catch (ConnectException e) {
            return new CalendarReply.Unavailable();
        } catch (IOException e) {
            // Refused before sending, or broken after the request may have arrived.
            return e.getCause() instanceof ConnectException ? new CalendarReply.Unavailable() : new CalendarReply.Unknown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CalendarReply.Unknown();
        } catch (RuntimeException e) {
            log.warn("Calendar answer could not be read: {}", e.getClass().getName());
            return new CalendarReply.Unknown();
        }
    }

    /** A {@code 422}: Dispatch broke the contract. Operators are alerted through the log; never retried. */
    private static CalendarReply defect(String operation, HttpResponse<String> response) {
        log.error("Calendar refused a {} as invalid (422): contract defect", operation);
        return new CalendarReply.Defect();
    }

    /** One booking as the calendar answers it. */
    private record Answer(String requestId, String externalRef, String status, Optional<TimeWindow> window) {

        boolean confirmed() {
            return "confirmed".equals(status);
        }

        boolean matches(BookingId id, Optional<TimeWindow> requested) {
            boolean sameWindow = requested.isEmpty() || window.equals(requested);
            return id.toString().equals(requestId) && externalRef != null && !externalRef.isBlank() && sameWindow;
        }
    }

    private Optional<Answer> booking(String body) {
        try {
            return answer(json.readValue(body, Map.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<List<Object>> items(String body) {
        try {
            Object items = json.readValue(body, Map.class).get("items");
            return items instanceof List<?> list ? Optional.of((List<Object>) list) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Answer> answer(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Optional<TimeWindow> window = Optional.empty();
        if (map.get("window") instanceof Map<?, ?> w && w.get("start") instanceof String start
                && w.get("end") instanceof String end) {
            try {
                Instant from = OffsetDateTime.parse(start).toInstant();
                Instant to = OffsetDateTime.parse(end).toInstant();
                window = to.isAfter(from) ? Optional.of(new TimeWindow(from, to)) : Optional.empty();
            } catch (RuntimeException e) {
                window = Optional.empty();
            }
        }
        return Optional.of(new Answer(text(map.get("requestId")), text(map.get("externalRef")),
                text(map.get("status")), window));
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String time(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(Objects.requireNonNull(instant));
    }
}
