package pt.isep.bmc.issue.application.query;

import java.time.Instant;
import java.util.List;
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
 * @param suggestion the unconfirmed suggestion, for administrators of the building only; empty once a
 *                   person decided, and when no suggestion was applied
 * @param summary    the published summary, or empty while the issue is not shared with the building
 * @param impact     the impact count and the caller's own confirmation; empty while there is no summary
 * @param subscribed the caller's own notification choice; off by default
 * @param criticalCheck Issue's own critical check, for administrators of the building only
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
        Optional<SuggestionView> suggestion,
        Optional<SummaryView> summary,
        Optional<ImpactView> impact,
        boolean subscribed,
        Optional<CriticalCheckView> criticalCheck) {

    public IssueView {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(triage, "triage");
        Objects.requireNonNull(suggestion, "suggestion");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(impact, "impact");
        Objects.requireNonNull(criticalCheck, "criticalCheck");
    }

    /** The same issue with the internal triage reason removed. */
    public IssueView withoutTriageReason() {
        return new IssueView(issueId, buildingId, buildingName, areaLabel, state, version, createdAt,
                triage.map(TriageView::withoutReason), suggestion, summary, impact, subscribed, criticalCheck);
    }

    /**
     * The same issue without what only its building's administrators see: the
     * unconfirmed suggestion and the critical check.
     */
    public IssueView withoutAdministratorOnly() {
        return new IssueView(issueId, buildingId, buildingName, areaLabel, state, version, createdAt, triage,
                Optional.empty(), summary, impact, subscribed, Optional.empty());
    }

    /** The same issue with the signals of its critical check. */
    public IssueView withCriticalSignals(List<CriticalCheckView.Signal> signals) {
        return new IssueView(issueId, buildingId, buildingName, areaLabel, state, version, createdAt, triage,
                suggestion, summary, impact, subscribed, criticalCheck.map(check -> check.withSignals(signals)));
    }
}
