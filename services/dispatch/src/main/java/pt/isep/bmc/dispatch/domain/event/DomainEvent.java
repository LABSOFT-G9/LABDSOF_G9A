package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;

/**
 * Something that happened in the Dispatch context that Issue needs
 * (technical design 4.4, {@code contracts/proto/bmc/dispatch/v1}). Written
 * to the outbox in the same transaction as the change; the CloudEvents
 * envelope is added by the outbox adapter. Every event carries the
 * intervention's state after the change, so Issue can apply it by version
 * (technical design 3.3).
 */
public sealed interface DomainEvent permits BookingConfirmed, BookingStateChanged, ContractorResponded,
        InterventionApproved, InterventionCancellationPending, InterventionCancelled, InterventionDeclined {

    /** CloudEvents {@code type}, for example {@code InterventionApproved}. */
    String type();

    /** CloudEvents {@code subject}: the intervention. */
    UUID aggregateId();

    /** The intervention's version after the change. */
    long aggregateVersion();

    BuildingId buildingId();

    Instant occurredAt();
}
