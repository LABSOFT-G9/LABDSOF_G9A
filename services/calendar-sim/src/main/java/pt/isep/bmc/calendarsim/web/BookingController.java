package pt.isep.bmc.calendarsim.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.calendarsim.SimProperties;
import pt.isep.bmc.calendarsim.booking.BookingRequest;
import pt.isep.bmc.calendarsim.booking.Calendar;
import pt.isep.bmc.calendarsim.booking.Operation;
import pt.isep.bmc.calendarsim.booking.Outcome;

/**
 * The calendar contract that Dispatch's adapter calls (technical design 4.5).
 * Each call first takes its scripted {@link Outcome}; without a script it
 * behaves like a working calendar.
 */
@RestController
class BookingController {

    private final Calendar calendar;
    private final SimProperties properties;

    BookingController(Calendar calendar, SimProperties properties) {
        this.calendar = calendar;
        this.properties = properties;
    }

    /**
     * {@code 201} new booking, {@code 200} the same booking again, {@code 409}
     * slot conflict, {@code 422} invalid request or a reused key with other
     * content, {@code 503} unavailable.
     */
    @PostMapping(path = "/bookings", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Bodies.CreateBooking body) {
        BookingRequest request = BookingRequests.parse(idempotencyKey, body);
        Outcome outcome = calendar.next(Operation.CREATE);
        switch (outcome.type()) {
            case UNAVAILABLE -> {
                return Problems.unavailable();
            }
            case CONFLICT -> {
                return conflict();
            }
            case INVALID -> {
                // Looks like a confirmation, but without the reference: nothing was booked.
                Bodies.BookingBody echo = new Bodies.BookingBody(request.requestId(), null, "confirmed", null);
                return ResponseEntity.status(HttpStatus.CREATED).body(echo);
            }
            case DELAY -> pause(outcome.delay());
            default -> {
                // OK and TIMEOUT_AFTER_CREATE book now.
            }
        }
        Calendar.CreateResult result = calendar.create(request);
        if (outcome.type() == Outcome.Type.TIMEOUT_AFTER_CREATE) {
            pause(properties.hang());
        }
        return switch (result) {
            case Calendar.CreateResult.Created created ->
                    ResponseEntity.status(HttpStatus.CREATED).body(Bodies.BookingBody.of(created.booking()));
            case Calendar.CreateResult.Replayed replayed -> ResponseEntity.ok(Bodies.BookingBody.of(replayed.booking()));
            case Calendar.CreateResult.KeyReused reused -> Problems.of(HttpStatus.UNPROCESSABLE_CONTENT,
                    "IDEMPOTENCY_KEY_REUSED", "This request ID was booked with other content.");
            case Calendar.CreateResult.Conflict c -> conflict();
        };
    }

    /** Reconciliation: the booking for this request ID, cancelled or not, or no item. */
    @GetMapping(path = "/bookings", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> find(@RequestParam(name = "requestId", required = false) String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Problems.invalid(List.of(Map.of("field", "requestId", "reason", "required")));
        }
        Outcome outcome = calendar.next(Operation.QUERY);
        switch (outcome.type()) {
            case UNAVAILABLE -> {
                return Problems.unavailable();
            }
            case INVALID -> {
                return ResponseEntity.ok(new Bodies.BookingList(
                        List.of(new Bodies.BookingBody(requestId, null, "confirmed", null))));
            }
            case DELAY -> pause(outcome.delay());
            default -> {
            }
        }
        return ResponseEntity.ok(new Bodies.BookingList(
                calendar.find(requestId).map(Bodies.BookingBody::of).stream().toList()));
    }

    /** {@code 200} cancelled (also when cancelled before), {@code 404} unknown reference, {@code 503}. */
    @PostMapping(path = "/bookings/{externalRef}/cancellation", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> cancel(
            @PathVariable String externalRef,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Problems.invalid(List.of(Map.of("field", "Idempotency-Key", "reason", "required")));
        }
        Outcome outcome = calendar.next(Operation.CANCEL);
        switch (outcome.type()) {
            case UNAVAILABLE -> {
                return Problems.unavailable();
            }
            case DELAY -> pause(outcome.delay());
            default -> {
            }
        }
        return calendar.cancel(externalRef)
                .<ResponseEntity<Object>>map(b -> ResponseEntity.ok(Bodies.BookingBody.of(b)))
                .orElseGet(() -> Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "No booking has this reference."));
    }

    @ExceptionHandler(BookingRequests.InvalidRequestException.class)
    ResponseEntity<Object> invalid(BookingRequests.InvalidRequestException e) {
        return Problems.invalid(e.errors());
    }

    private static ResponseEntity<Object> conflict() {
        return Problems.of(HttpStatus.CONFLICT, "SLOT_CONFLICT", "The contractor is already booked in this window.");
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
