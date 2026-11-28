package pt.isep.bmc.issue.domain.issue;

import java.util.Objects;
import java.util.UUID;

/**
 * A triage suggestion as it arrived in {@code TriageSuggested} (technical
 * design 4.4), before any check. Every text field may be {@code null}.
 *
 * @param eventId       the ID of the event that carried the suggestion; a
 *                      suggestion is recorded once per event
 * @param sourceVersion the issue version the suggestion was computed from
 */
public record SuggestionInput(
        UUID eventId,
        long sourceVersion,
        String category,
        String urgency,
        String rationale,
        boolean needsReview,
        String origin,
        String modelVersion,
        String promptVersion,
        String rulesVersion,
        String outputSchemaVersion,
        String failureCode) {

    public SuggestionInput {
        Objects.requireNonNull(eventId, "eventId");
    }

    /** Never prints the rationale: it is written from a private report. */
    @Override
    public String toString() {
        return "SuggestionInput[eventId=" + eventId + ", sourceVersion=" + sourceVersion + ", origin=" + origin + "]";
    }
}
