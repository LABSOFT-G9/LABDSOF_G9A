package pt.isep.bmc.issue.application.query;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.SuggestionOrigin;
import pt.isep.bmc.issue.domain.issue.SuggestionRationale;

/**
 * The unconfirmed triage suggestion of an issue, for the building's
 * administrators only. Unverified: it is not the issue's category or urgency.
 *
 * @param rationale    internal, and never in lists: it is written from a private report
 * @param failureCode  why the AI result was not used; with origin {@code fallback}
 * @param suggestedAt  when Issue recorded the suggestion
 * @param rulesVersion empty when the rules did not run
 * @param modelVersion empty when no AI provider was called
 */
public record SuggestionView(
        IssueCategory category,
        IssueUrgency urgency,
        SuggestionOrigin origin,
        Optional<SuggestionRationale> rationale,
        boolean needsReview,
        Optional<String> failureCode,
        Instant suggestedAt,
        Optional<String> rulesVersion,
        Optional<String> modelVersion) {

    public SuggestionView {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(urgency, "urgency");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(rationale, "rationale");
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(suggestedAt, "suggestedAt");
        Objects.requireNonNull(rulesVersion, "rulesVersion");
        Objects.requireNonNull(modelVersion, "modelVersion");
    }
}
