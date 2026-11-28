package pt.isep.bmc.dispatch.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.booking.BookingSource;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/** {@code bmc.dispatch.v1.BookingConfirmed}: by the calendar or by hand; a manual one is never shown as synchronised. */
public record BookingConfirmed(
        UUID aggregateId,
        long aggregateVersion,
        BuildingId buildingId,
        Instant occurredAt,
        IssueId issueId,
        TimeWindow window,
        BookingSource source,
        InterventionState interventionState) implements DomainEvent {

    @Override
    public String type() {
        return "BookingConfirmed";
    }
}
