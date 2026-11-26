package pt.isep.bmc.calendarsim.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import pt.isep.bmc.calendarsim.booking.BookingRequest;
import pt.isep.bmc.calendarsim.booking.Window;

/**
 * Checks a booking request (technical design 4.5). Anything wrong answers
 * {@code 422}: for Dispatch, a contract defect that it never retries.
 */
final class BookingRequests {

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final int CONTRACTOR_REF_MAX = 64;
    private static final int BUILDING_CODE_MAX = 64;
    private static final int SUMMARY_MAX = 200;

    private BookingRequests() {
    }

    static BookingRequest parse(String idempotencyKey, Bodies.CreateBooking body) {
        Bodies.CreateBooking b = body == null ? new Bodies.CreateBooking(null, null, null, null, null) : body;
        List<Map<String, String>> errors = new ArrayList<>();
        if (b.requestId() == null || !REQUEST_ID.matcher(b.requestId()).matches()) {
            errors.add(error("requestId", b.requestId() == null ? "required" : "invalid_format"));
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            errors.add(error("Idempotency-Key", "required"));
        } else if (!idempotencyKey.equals(b.requestId())) {
            errors.add(error("Idempotency-Key", "not_request_id"));
        }
        text(b.contractorRef(), "contractorRef", CONTRACTOR_REF_MAX, errors);
        text(b.buildingCode(), "buildingCode", BUILDING_CODE_MAX, errors);
        text(b.summary(), "summary", SUMMARY_MAX, errors);
        Bodies.WindowBody window = b.window() == null ? new Bodies.WindowBody(null, null) : b.window();
        Instant start = instant(window.start(), "window.start", errors);
        Instant end = instant(window.end(), "window.end", errors);
        if (start != null && end != null && !end.isAfter(start)) {
            errors.add(error("window.end", "not_after_start"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        return new BookingRequest(b.requestId(), b.contractorRef(), new Window(start, end), b.buildingCode(),
                b.summary());
    }

    private static void text(String value, String field, int max, List<Map<String, String>> errors) {
        if (value == null || value.isBlank()) {
            errors.add(error(field, "required"));
        } else if (value.codePointCount(0, value.length()) > max) {
            errors.add(error(field, "too_long"));
        } else if (value.codePoints().anyMatch(Character::isISOControl)) {
            errors.add(error(field, "invalid_characters"));
        }
    }

    private static Instant instant(String value, String field, List<Map<String, String>> errors) {
        if (value == null || value.isBlank()) {
            errors.add(error(field, "required"));
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            errors.add(error(field, "invalid_format"));
            return null;
        }
    }

    private static Map<String, String> error(String field, String reason) {
        return Map.of("field", field, "reason", reason);
    }

    /** {@code 422 INVALID_REQUEST} with every invalid field. */
    static final class InvalidRequestException extends RuntimeException {

        private final transient List<Map<String, String>> errors;

        InvalidRequestException(List<Map<String, String>> errors) {
            super("invalid booking request", null, false, false);
            this.errors = List.copyOf(errors);
        }

        List<Map<String, String>> errors() {
            return errors;
        }
    }
}
