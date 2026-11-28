package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/**
 * {@code bmc.dispatch.v1.BookingStateChanged}: a booking became {@code
 * uncertain}, {@code rejected}, {@code failed} or a {@code discrepancy}. The
 * intervention does not change, so the event carries its current version and
 * Issue keeps it in history (technical design 3.3).
 */
public record BookingStateChanged(
        UUID aggregateId,
        long aggregateVersion,
        BuildingId buildingId,
        Instant occurredAt,
        IssueId issueId,
        BookingState bookingState,
        InterventionState interventionState) implements DomainEvent {

    @Override
    public String type() {
        return "BookingStateChanged";
    }
}
