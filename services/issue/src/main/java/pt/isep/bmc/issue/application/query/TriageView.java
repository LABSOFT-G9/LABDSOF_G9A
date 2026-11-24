package pt.isep.bmc.issue.application.query;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.TriageReason;

/**
 * The current human triage decision of an issue, as a caller may see it.
 *
 * @param reason internal: present only for administrators of the building, and never in lists
 */
public record TriageView(IssueCategory category, IssueUrgency urgency, Optional<TriageReason> reason, Instant decidedAt) {

    public TriageView {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(urgency, "urgency");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }

    /** The same decision without its reason. */
    public TriageView withoutReason() {
        return new TriageView(category, urgency, Optional.empty(), decidedAt);
    }
}
