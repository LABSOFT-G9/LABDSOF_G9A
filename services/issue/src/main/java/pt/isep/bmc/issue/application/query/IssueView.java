package pt.isep.bmc.issue.application.query;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;

/**
 * One issue as {@code GET /issues} and {@code GET /issues/{id}} show it. No
 * report text and nobody's identity: the area label says what it is about.
 *
 * @param triage the current human decision, or empty while category and urgency are undecided
 */
public record IssueView(
        IssueId issueId,
        BuildingId buildingId,
        String buildingName,
        String areaLabel,
        IssueState state,
        long version,
        Instant createdAt,
        Optional<TriageView> triage) {

    public IssueView {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(triage, "triage");
    }

    /** The same issue with the internal triage reason removed. */
    public IssueView withoutTriageReason() {
        return new IssueView(issueId, buildingId, buildingName, areaLabel, state, version, createdAt,
                triage.map(TriageView::withoutReason));
    }
}
