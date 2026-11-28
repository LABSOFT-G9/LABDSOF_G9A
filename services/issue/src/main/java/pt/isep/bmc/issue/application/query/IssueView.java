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
 * @param triage     the current human decision, or empty while category and urgency are undecided
 * @param summary    the published summary, or empty while the issue is not shared with the building
 * @param impact     the impact count and the caller's own confirmation; empty while there is no summary
 * @param subscribed the caller's own notification choice; off by default
 */
public record IssueView(
        IssueId issueId,
        BuildingId buildingId,
        String buildingName,
        String areaLabel,
        IssueState state,
        long version,
        Instant createdAt,
        Optional<TriageView> triage,
        Optional<SummaryView> summary,
        Optional<ImpactView> impact,
        boolean subscribed) {

    public IssueView {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(triage, "triage");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(impact, "impact");
    }

    /** The same issue with the internal triage reason removed. */
    public IssueView withoutTriageReason() {
        return new IssueView(issueId, buildingId, buildingName, areaLabel, state, version, createdAt,
                triage.map(TriageView::withoutReason), summary, impact, subscribed);
    }
}
