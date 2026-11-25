package pt.isep.bmc.issue.application.query;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.TriageReason;

/**
 * One history entry as a caller may see it. Never says who acted, only in
 * which role.
 *
 * @param type     wire value, for example {@code triage_decided}
 * @param category the decided category, on a triage decision only
 * @param reason   internal: present only for administrators of the building
 */
public record IssueHistoryItem(
        int sequence,
        String type,
        HistoryVisibility visibility,
        String actorRole,
        Instant occurredAt,
        Optional<IssueCategory> category,
        Optional<IssueUrgency> urgency,
        Optional<TriageReason> reason) {

    public IssueHistoryItem {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(urgency, "urgency");
        Objects.requireNonNull(reason, "reason");
    }

    /** The same entry with the internal reason removed. */
    public IssueHistoryItem withoutReason() {
        return new IssueHistoryItem(sequence, type, visibility, actorRole, occurredAt, category, urgency, Optional.empty());
    }
}
