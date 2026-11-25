package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ValidationException;

/**
 * Records a triage suggestion from the AI triage service (US-03, technical
 * design 3.1 and 3.3).
 *
 * <ul>
 *   <li>A suggestion that does not match the contract is refused whole
 *       ({@link ValidationException}): nothing of it is stored.</li>
 *   <li>A suggestion that names the version triage was asked for, while the
 *       issue is still {@code received}, moves it to {@code in_triage} with the
 *       next version. A summary published in between does not prevent it.</li>
 *   <li>Any other suggestion is late: a person already decided, another
 *       suggestion was applied, or it names a version triage was never asked
 *       for. It is stored and shown in the internal history, and changes
 *       nothing else.</li>
 * </ul>
 * A suggestion never sets the issue's category or urgency and never touches
 * its critical check.
 */
public final class SuggestionIntake {

    static final String FIELD_ISSUE_VERSION = "issueVersion";
    static final String FIELD_CATEGORY = "category";
    static final String FIELD_URGENCY = "urgency";
    static final String FIELD_RATIONALE = "rationale";
    static final String FIELD_ORIGIN = "origin";

    private SuggestionIntake() {
    }

    /**
     * @param sequence  the sequence number for the new history entry
     * @param areaLabel the label of the issue's area, for {@code IssueStateChanged}
     * @throws ValidationException with every invalid field
     */
    public static SuggestionOutcome record(
            Issue issue, SuggestionInput input, int sequence, String areaLabel, Instant now) {
        List<FieldViolation> violations = new ArrayList<>();
        if (input.sourceVersion() < Issue.INITIAL_VERSION) {
            violations.add(new FieldViolation(FIELD_ISSUE_VERSION, "invalid"));
        }
        Optional<IssueCategory> category =
                choice(input.category(), FIELD_CATEGORY, IssueCategory.fromValue(input.category()), violations);
        Optional<IssueUrgency> urgency =
                choice(input.urgency(), FIELD_URGENCY, IssueUrgency.fromValue(input.urgency()), violations);
        Optional<SuggestionOrigin> origin =
                choice(input.origin(), FIELD_ORIGIN, SuggestionOrigin.fromValue(input.origin()), violations);
        SuggestionRationale.violation(input.rationale())
                .ifPresent(reason -> violations.add(new FieldViolation(FIELD_RATIONALE, reason)));
        violations.addAll(SuggestionProvenance.violations(input));
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        Optional<Issue> inTriage = issue.suggestTriage(input.sourceVersion(), now);
        Issue after = inTriage.orElse(issue);
        TriageSuggestion suggestion = new TriageSuggestion(
                input.eventId(),
                issue.id(),
                input.sourceVersion(),
                sequence,
                inTriage.isPresent(),
                category.orElseThrow(),
                urgency.orElseThrow(),
                SuggestionRationale.of(input.rationale()),
                input.needsReview(),
                origin.orElseThrow(),
                SuggestionProvenance.of(input),
                now);
        List<DomainEvent> events = inTriage
                .<List<DomainEvent>>map(changed -> List.of(IssueStateChanged.of(changed, areaLabel)))
                .orElse(List.of());
        return new SuggestionOutcome(inTriage, suggestion, IssueHistoryEntry.triageSuggested(after, sequence, now), events);
    }

    private static <T> Optional<T> choice(String raw, String field, Optional<T> parsed, List<FieldViolation> violations) {
        if (raw == null || raw.isBlank()) {
            violations.add(new FieldViolation(field, "required"));
        } else if (parsed.isEmpty()) {
            violations.add(new FieldViolation(field, "invalid"));
        }
        return parsed;
    }
}
