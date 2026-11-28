package pt.isep.bmc.notification.adapters.in.messaging;

import java.util.regex.Pattern;

/**
 * The CloudEvents attributes the consumer logs: the event's {@code id} and
 * its {@code correlationid} (technical design 4.4). Values that are not short
 * safe tokens are replaced, so an envelope cannot inject text into the logs.
 */
public record EventEnvelope(String eventId, String correlationId) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final String UNKNOWN = "unknown";

    public EventEnvelope {
        eventId = safe(eventId);
        correlationId = safe(correlationId);
    }

    private static String safe(String value) {
        return value != null && SAFE.matcher(value).matches() ? value : UNKNOWN;
    }
}
