package pt.isep.bmc.issue.domain.dispatch;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.issue.domain.issue.IssueId;

/**
 * Issue's copy of one intervention of an issue (technical design 2.1): its
 * state and the last version applied, so an older or repeated event never
 * moves it back. At most one intervention of an issue is not terminal: the
 * active one.
 *
 * @param contractorUserId the assigned contractor's account, only to address notifications; it grants nothing
 */
public record InterventionProjection(
        UUID interventionId,
        IssueId issueId,
        InterventionState state,
        long lastVersion,
        Optional<String> contractorUserId,
        Instant firstAppliedAt,
        Instant updatedAt) {

    public InterventionProjection {
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(contractorUserId, "contractorUserId");
        Objects.requireNonNull(firstAppliedAt, "firstAppliedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (lastVersion < 1) {
            throw new IllegalArgumentException("lastVersion starts at 1");
        }
    }

    /** The first event of an intervention Issue had not seen. */
    static InterventionProjection first(DispatchEvent event, Instant now) {
        return new InterventionProjection(event.interventionId(), event.issueId(), event.interventionState(),
                event.aggregateVersion(), event.contractorUserId(), now, now);
    }

    /** A newer event of this intervention: its state and version, and the contractor when the event names one. */
    InterventionProjection next(DispatchEvent event, Instant now) {
        return new InterventionProjection(interventionId, issueId, event.interventionState(), event.aggregateVersion(),
                event.contractorUserId().or(() -> contractorUserId), firstAppliedAt, now);
    }
}
