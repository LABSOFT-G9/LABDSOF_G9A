package pt.isep.bmc.issue.application.port.out;

import java.util.Objects;
import java.util.UUID;
import pt.isep.bmc.issue.domain.event.DomainEvent;

/**
 * A domain event with the envelope data the application owns: its
 * {@code eventId} (CloudEvents {@code id}) and the request's correlation ID.
 */
public record OutboxEvent(UUID eventId, DomainEvent event, String correlationId) {

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
