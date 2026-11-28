package pt.isep.bmc.issue.domain.dispatch;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueId;

/**
 * One Dispatch event as Issue needs it: who it is about, which version of
 * the intervention it carries, and the intervention's full new state
 * (technical design 3.3, 4.4). Holds codes, never free text.
 *
 * @param eventId           the CloudEvents {@code id}, which makes a repeated delivery a no-op
 * @param interventionId    the CloudEvents {@code subject}: the intervention is the aggregate
 * @param aggregateVersion  the intervention's version after the change
 * @param code              the decision ({@code ContractorResponded}), the reason code (decline,
 *                          cancellation) or the booking state ({@code BookingStateChanged})
 * @param window            the booked window ({@code BookingConfirmed})
 * @param manual            a booking confirmed by hand rather than by the calendar
 * @param contractorUserId  the contractor's account ({@code InterventionApproved}, and
 *                          {@code InterventionTermsChanged} when they had the request), kept only
 *                          to address notifications
 */
public record DispatchEvent(
        String eventId,
        DispatchEventType type,
        UUID interventionId,
        long aggregateVersion,
        BuildingId buildingId,
        IssueId issueId,
        InterventionState interventionState,
        Instant occurredAt,
        Optional<String> code,
        Optional<VisitWindow> window,
        boolean manual,
        Optional<String> contractorUserId) {

    public DispatchEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(interventionState, "interventionState");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(contractorUserId, "contractorUserId");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (aggregateVersion < 1) {
            throw new IllegalArgumentException("aggregateVersion starts at 1");
        }
    }
}
