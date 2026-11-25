package pt.isep.bmc.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A received CloudEvents 1.0 message in structured JSON mode, with the BMC
 * extension attributes (technical design 4.4). {@code data} is the payload in
 * the proto3 JSON mapping of its {@code .proto} message.
 *
 * <p>{@link #toString()} leaves the payload out: {@code TriageRequested}
 * carries report text and payloads are never logged.
 */
public record CloudEvent(
        String id,
        String type,
        String source,
        String subject,
        Instant time,
        int schemaVersion,
        long aggregateVersion,
        String buildingId,
        String correlationId,
        String traceparent,
        JsonNode data) {

    static final String SPEC_VERSION = "1.0";

    /** The payload as JSON text, for example for Protobuf's {@code JsonFormat} parser. */
    public String dataJson() {
        return data.toString();
    }

    /**
     * Reads a message body. Every attribute of the envelope is required except
     * {@code traceparent}.
     *
     * @throws InvalidEventException if the body is not such an envelope
     */
    public static CloudEvent parse(byte[] body, JsonMapper json) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw new InvalidEventException("the body is not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidEventException("the body is not a JSON object");
        }
        String specVersion = text(root, "specversion");
        if (!SPEC_VERSION.equals(specVersion)) {
            throw new InvalidEventException("specversion is not " + SPEC_VERSION);
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            throw new InvalidEventException("data is missing or not an object");
        }
        return new CloudEvent(
                text(root, "id"),
                text(root, "type"),
                text(root, "source"),
                text(root, "subject"),
                time(root),
                (int) integer(root, "schemaversion", Integer.MAX_VALUE),
                integer(root, "aggregateversion", Long.MAX_VALUE),
                text(root, "buildingid"),
                text(root, "correlationid"),
                optionalText(root, "traceparent"),
                data);
    }

    private static String text(JsonNode root, String name) {
        String value = optionalText(root, name);
        if (value == null || value.isBlank()) {
            throw new InvalidEventException(name + " is missing");
        }
        return value;
    }

    private static String optionalText(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new InvalidEventException(name + " is not a string");
        }
        return node.stringValue();
    }

    private static long integer(JsonNode root, String name, long max) {
        JsonNode node = root.get(name);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidEventException(name + " is missing or not an integer");
        }
        long value = node.longValue();
        if (value < 0 || value > max) {
            throw new InvalidEventException(name + " is out of range");
        }
        return value;
    }

    private static Instant time(JsonNode root) {
        try {
            return Instant.parse(text(root, "time"));
        } catch (DateTimeParseException e) {
            throw new InvalidEventException("time is not an RFC 3339 timestamp");
        }
    }

    @Override
    public String toString() {
        return "CloudEvent[id=" + id + ", type=" + type + ", source=" + source + ", subject=" + subject
                + ", schemaVersion=" + schemaVersion + ", aggregateVersion=" + aggregateVersion + "]";
    }
}
