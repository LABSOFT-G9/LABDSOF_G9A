package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.SuggestionInput;
import pt.isep.bmc.issue.domain.issue.SuggestionIntake;
import pt.isep.bmc.issue.domain.issue.SuggestionOrigin;
import pt.isep.bmc.issue.domain.issue.SuggestionOutcome;
import pt.isep.bmc.issue.domain.issue.SuggestionProvenance;
import pt.isep.bmc.issue.domain.issue.SummaryId;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ValidationException;

/** US-03: a suggestion is recorded, never decided, and a late one changes nothing (technical design 3.1, 3.3). */
class SuggestionIntakeTest {

    static final IssueId ID = new IssueId(UUID.randomUUID());
    static final BuildingId BUILDING = new BuildingId(UUID.randomUUID());
    static final AreaId AREA = new AreaId(UUID.randomUUID());
    static final Instant CREATED = Instant.parse("2026-11-24T10:00:00Z");
    static final Instant NOW = Instant.parse("2026-11-24T10:00:30Z");
    static final UUID EVENT = UUID.randomUUID();
    static final String RATIONALE = "The report mentions a leak under a sink.";

    static Issue issue(IssueState state, long version) {
        boolean decided = state != IssueState.RECEIVED && state != IssueState.IN_TRIAGE;
        return Issue.restore(ID, BUILDING, AREA, state, version,
                decided ? IssueCategory.OTHER : null, decided ? IssueUrgency.LOW : null,
                CriticalCheckStatus.PENDING, CREATED, CREATED);
    }

    static SuggestionInput ai(long sourceVersion) {
        return new SuggestionInput(EVENT, sourceVersion, "plumbing", "high", RATIONALE, true, "ai",
                "model-x-1", "triage-v1", null, "triage-output-v1", null);
    }

    @Test
    void aSuggestionForTheRequestedVersionMovesAReceivedIssueToInTriageAndDecidesNothing() {
        SuggestionOutcome outcome = SuggestionIntake.record(issue(IssueState.RECEIVED, 1), ai(1), 2, "Garage", NOW);

        Issue next = outcome.issue().orElseThrow();
        assertThat(next.state()).isEqualTo(IssueState.IN_TRIAGE);
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.category()).isEmpty();
        assertThat(next.urgency()).isEmpty();
        assertThat(next.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.PENDING);
        assertThat(next.updatedAt()).isEqualTo(NOW);

        assertThat(outcome.suggestion().applied()).isTrue();
        assertThat(outcome.suggestion().eventId()).isEqualTo(EVENT);
        assertThat(outcome.suggestion().issueId()).isEqualTo(ID);
        assertThat(outcome.suggestion().sourceVersion()).isEqualTo(1);
        assertThat(outcome.suggestion().historySequence()).isEqualTo(2);
        assertThat(outcome.suggestion().category()).isEqualTo(IssueCategory.PLUMBING);
        assertThat(outcome.suggestion().urgency()).isEqualTo(IssueUrgency.HIGH);
        assertThat(outcome.suggestion().rationale().value()).isEqualTo(RATIONALE);
        assertThat(outcome.suggestion().needsReview()).isTrue();
        assertThat(outcome.suggestion().origin()).isEqualTo(SuggestionOrigin.AI);
        assertThat(outcome.suggestion().provenance()).isEqualTo(new SuggestionProvenance(Optional.of("model-x-1"),
                Optional.of("triage-v1"), Optional.empty(), Optional.of("triage-output-v1"), Optional.empty()));
        assertThat(outcome.suggestion().recordedAt()).isEqualTo(NOW);

        assertThat(outcome.history()).isEqualTo(new IssueHistoryEntry(ID, 2, HistoryEntryType.TRIAGE_SUGGESTED,
                HistoryVisibility.INTERNAL, IssueHistoryEntry.ROLE_SYSTEM, 2, NOW));
        assertThat(outcome.events()).containsExactly(IssueStateChanged.of(next, "Garage"));
        IssueStateChanged event = (IssueStateChanged) outcome.events().get(0);
        assertThat(event.category()).isEmpty();
        assertThat(event.urgency()).isEmpty();
        assertThat(event.dispatchable()).isFalse();
    }

    /** Publishing moves the version, not the report the suggestion was computed from. */
    @Test
    void aSuggestionStillAppliesAfterASummaryWasPublished() {
        SummaryId summary = new SummaryId(UUID.randomUUID());
        Issue shared = issue(IssueState.RECEIVED, 1).publishSummary(1, summary, CREATED.plusSeconds(5));

        SuggestionOutcome outcome = SuggestionIntake.record(shared, ai(1), 3, "Garage", NOW);

        Issue next = outcome.issue().orElseThrow();
        assertThat(next.state()).isEqualTo(IssueState.IN_TRIAGE);
        assertThat(next.version()).isEqualTo(3);
        assertThat(next.publishedSummaryId()).contains(summary);
        assertThat(outcome.suggestion().applied()).isTrue();
        assertThat(outcome.suggestion().sourceVersion()).isEqualTo(1);
        assertThat(outcome.history().issueVersion()).isEqualTo(3);
        assertThat(outcome.events()).containsExactly(IssueStateChanged.of(next, "Garage"));
    }

    @Test
    void aSuggestionForAVersionTriageWasNotAskedForIsKeptAsLateAndChangesNothing() {
        Issue current = issue(IssueState.RECEIVED, 2);

        SuggestionOutcome outcome = SuggestionIntake.record(current, ai(2), 3, "Garage", NOW);

        assertThat(outcome.issue()).isEmpty();
        assertThat(outcome.events()).isEmpty();
        assertThat(outcome.suggestion().applied()).isFalse();
        assertThat(outcome.suggestion().category()).isEqualTo(IssueCategory.PLUMBING);
        // The entry keeps the version the issue already had.
        assertThat(outcome.history().issueVersion()).isEqualTo(2);
        assertThat(outcome.history().occurredAt()).isEqualTo(NOW);
        assertThat(outcome.history().visibility()).isEqualTo(HistoryVisibility.INTERNAL);
    }

    @ParameterizedTest
    @EnumSource(value = IssueState.class, names = "RECEIVED", mode = EnumSource.Mode.EXCLUDE)
    void aSuggestionNeverChangesAnIssueThatLeftReceived(IssueState state) {
        Issue current = issue(state, 5);

        // Even one that answers the triage request.
        SuggestionOutcome outcome = SuggestionIntake.record(current, ai(1), 4, "Garage", NOW);

        assertThat(outcome.issue()).isEmpty();
        assertThat(outcome.events()).isEmpty();
        assertThat(outcome.suggestion().applied()).isFalse();
    }

    @Test
    void aLateSuggestionNeverOverwritesAHumanDecision() {
        Issue decided = issue(IssueState.RECEIVED, 1)
                .decideTriage(1, IssueCategory.ELECTRICAL, IssueUrgency.CRITICAL, CREATED.plusSeconds(5));

        SuggestionOutcome outcome = SuggestionIntake.record(decided, ai(1), 3, "Garage", NOW);

        assertThat(outcome.issue()).isEmpty();
        assertThat(outcome.suggestion().applied()).isFalse();
        assertThat(decided.category()).contains(IssueCategory.ELECTRICAL);
        assertThat(decided.urgency()).contains(IssueUrgency.CRITICAL);
    }

    @Test
    void everyInvalidFieldIsReportedAndNothingIsProduced() {
        SuggestionInput input = new SuggestionInput(EVENT, 0, "gardening", " ", "x".repeat(241), false, "human",
                "m".repeat(101), "has space", null, "", "bell\u0007");

        assertThatThrownBy(() -> SuggestionIntake.record(issue(IssueState.RECEIVED, 1), input, 2, "Garage", NOW))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(
                                new FieldViolation("issueVersion", "invalid"),
                                new FieldViolation("category", "invalid"),
                                new FieldViolation("urgency", "required"),
                                new FieldViolation("origin", "invalid"),
                                new FieldViolation("rationale", "too_long"),
                                new FieldViolation("modelVersion", "too_long"),
                                new FieldViolation("promptVersion", "invalid_characters"),
                                new FieldViolation("failureCode", "invalid_characters")));
    }

    @Test
    void labelsAreTrimmedAndABlankLabelIsAbsent() {
        SuggestionInput input = new SuggestionInput(EVENT, 1, "other", "low", "  " + RATIONALE + "  ", false,
                "fallback", "  ", null, " rules-v1 ", "", "timeout");

        SuggestionOutcome outcome = SuggestionIntake.record(issue(IssueState.RECEIVED, 1), input, 2, "Garage", NOW);

        assertThat(outcome.suggestion().origin()).isEqualTo(SuggestionOrigin.FALLBACK);
        assertThat(outcome.suggestion().rationale().value()).isEqualTo(RATIONALE);
        assertThat(outcome.suggestion().needsReview()).isFalse();
        assertThat(outcome.suggestion().provenance()).isEqualTo(new SuggestionProvenance(Optional.empty(),
                Optional.empty(), Optional.of("rules-v1"), Optional.empty(), Optional.of("timeout")));
    }

    @Test
    void theRationaleIsNeverPrinted() {
        SuggestionOutcome outcome = SuggestionIntake.record(issue(IssueState.RECEIVED, 1), ai(1), 2, "Garage", NOW);

        assertThat(ai(1).toString()).doesNotContain("leak");
        assertThat(outcome.suggestion().toString()).doesNotContain("leak");
        assertThat(outcome.toString()).doesNotContain("leak");
    }
}
