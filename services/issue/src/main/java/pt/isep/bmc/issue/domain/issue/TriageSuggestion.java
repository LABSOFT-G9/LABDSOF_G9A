package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A recorded triage suggestion (technical design 2.1): what a suggester
 * proposed for one version of an issue. Unverified, and never a decision:
 * category and urgency of the issue stay undecided until a person decides.
 * Every suggestion that arrives is kept, including a late one.
 *
 * @param eventId         the event that carried it; the same event is recorded once
 * @param sourceVersion   the issue version it was computed from
 * @param historySequence the issue's history entry that records its arrival
 * @param applied         whether it moved the issue to {@code in_triage}; false
 *                        for a late one, which changed nothing
 */
public record TriageSuggestion(
        UUID eventId,
        IssueId issueId,
        long sourceVersion,
        int historySequence,
        boolean applied,
        IssueCategory category,
        IssueUrgency urgency,
        SuggestionRationale rationale,
        boolean needsReview,
        SuggestionOrigin origin,
        SuggestionProvenance provenance,
        Instant recordedAt) {

    public TriageSuggestion {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(urgency, "urgency");
        Objects.requireNonNull(rationale, "rationale");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    /** Never prints the rationale. */
    @Override
    public String toString() {
        return "TriageSuggestion[eventId=" + eventId + ", issueId=" + issueId + ", sourceVersion=" + sourceVersion
                + ", applied=" + applied + ", origin=" + origin.value() + "]";
    }
}
