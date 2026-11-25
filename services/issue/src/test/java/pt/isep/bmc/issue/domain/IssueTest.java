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
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.SummaryId;
import pt.isep.bmc.issue.domain.shared.InvalidTransitionException;
import pt.isep.bmc.issue.domain.shared.VersionConflictException;

/** The triage rows of the issue state table (technical design 3.1 and 3.3). */
class IssueTest {

    static final Instant CREATED = Instant.parse("2026-11-24T09:00:00Z");
    static final Instant LATER = Instant.parse("2026-11-24T10:00:00Z");
    static final IssueId ID = new IssueId(UUID.randomUUID());
    static final BuildingId BUILDING = new BuildingId(UUID.randomUUID());
    static final AreaId AREA = new AreaId(UUID.randomUUID());

    static Issue issue(IssueState state, long version) {
        boolean decided = state != IssueState.RECEIVED && state != IssueState.IN_TRIAGE;
        return Issue.restore(ID, BUILDING, AREA, state, version,
                decided ? IssueCategory.OTHER : null, decided ? IssueUrgency.LOW : null,
                CriticalCheckStatus.PENDING, CREATED, CREATED);
    }

    @Test
    void aNewIssueHasNothingDecided() {
        Issue received = Issue.receive(ID, BUILDING, AREA, CREATED);
        assertThat(received.state()).isEqualTo(IssueState.RECEIVED);
        assertThat(received.version()).isEqualTo(1);
        assertThat(received.category()).isEmpty();
        assertThat(received.urgency()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = IssueState.class, names = {"RECEIVED", "IN_TRIAGE", "TRIAGED"})
    void manualTriageMovesToTriagedWithTheDecisionAndTheNextVersion(IssueState from) {
        Issue triaged = issue(from, 4).decideTriage(4, IssueCategory.PLUMBING, IssueUrgency.HIGH, LATER);

        assertThat(triaged.state()).isEqualTo(IssueState.TRIAGED);
        assertThat(triaged.version()).isEqualTo(5);
        assertThat(triaged.category()).contains(IssueCategory.PLUMBING);
        assertThat(triaged.urgency()).contains(IssueUrgency.HIGH);
        assertThat(triaged.updatedAt()).isEqualTo(LATER);
        assertThat(triaged.createdAt()).isEqualTo(CREATED);
        assertThat(triaged.buildingId()).isEqualTo(BUILDING);
    }

    @ParameterizedTest
    @EnumSource(value = IssueState.class, names = {"RECEIVED", "IN_TRIAGE", "TRIAGED"}, mode = EnumSource.Mode.EXCLUDE)
    void triageIsRejectedOnceAnInterventionStartedOrTheIssueIsClosed(IssueState from) {
        Issue issue = issue(from, 7);
        assertThatThrownBy(() -> issue.decideTriage(7, IssueCategory.PLUMBING, IssueUrgency.HIGH, LATER))
                .isInstanceOfSatisfying(InvalidTransitionException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_TRANSITION");
                    assertThat(e.currentState()).isEqualTo(from.value());
                });
    }

    @Test
    void aStaleVersionIsAConflictThatReportsTheCurrentVersionAndState() {
        Issue issue = issue(IssueState.TRIAGED, 3);
        assertThatThrownBy(() -> issue.decideTriage(2, IssueCategory.LIFT, IssueUrgency.LOW, LATER))
                .isInstanceOfSatisfying(VersionConflictException.class, e -> {
                    assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(e.currentVersion()).isEqualTo(3);
                    assertThat(e.currentState()).isEqualTo("triaged");
                });
        // A version from the future is just as wrong.
        assertThatThrownBy(() -> issue.decideTriage(4, IssueCategory.LIFT, IssueUrgency.LOW, LATER))
                .isInstanceOf(VersionConflictException.class);
    }

    @Test
    void aStaleVersionIsReportedBeforeAnInvalidTransition() {
        Issue scheduled = issue(IssueState.SCHEDULED, 9);
        assertThatThrownBy(() -> scheduled.decideTriage(2, IssueCategory.LIFT, IssueUrgency.LOW, LATER))
                .isInstanceOf(VersionConflictException.class);
    }

    @Test
    void aSuggestionForTheRequestedVersionMovesAReceivedIssueToInTriageWithoutDecidingAnything() {
        Optional<Issue> next = issue(IssueState.RECEIVED, 1).suggestTriage(1, LATER);

        assertThat(next).isPresent();
        assertThat(next.get().state()).isEqualTo(IssueState.IN_TRIAGE);
        assertThat(next.get().version()).isEqualTo(2);
        assertThat(next.get().category()).as("a suggestion is not a decision").isEmpty();
        assertThat(next.get().urgency()).isEmpty();
    }

    /** Late-result fixture (US-02): the AI answers after the administrator decided. */
    @Test
    void aLateSuggestionNeverOverwritesAHumanDecision() {
        Issue received = Issue.receive(ID, BUILDING, AREA, CREATED);
        Issue decided = received.decideTriage(1, IssueCategory.ELECTRICAL, IssueUrgency.CRITICAL, LATER);

        // The suggestion answers the request (version 1), but the issue is now triaged.
        assertThat(decided.suggestTriage(1, LATER.plusSeconds(60))).isEmpty();
        // Naming the current version changes nothing either.
        assertThat(decided.suggestTriage(2, LATER.plusSeconds(60))).isEmpty();
        assertThat(decided.category()).contains(IssueCategory.ELECTRICAL);
        assertThat(decided.urgency()).contains(IssueUrgency.CRITICAL);
        assertThat(decided.version()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = IssueState.class, names = "RECEIVED", mode = EnumSource.Mode.EXCLUDE)
    void aSuggestionChangesNothingOutsideReceived(IssueState state) {
        assertThat(issue(state, 5).suggestTriage(1, LATER)).isEmpty();
        assertThat(issue(state, 5).suggestTriage(5, LATER)).isEmpty();
    }

    /** The request names version 1 ({@code TriageRequested}); nothing else was ever asked for. */
    @Test
    void aSuggestionThatNamesAVersionTriageWasNotAskedForIsIgnored() {
        assertThat(Issue.receive(ID, BUILDING, AREA, CREATED).triageRequestVersion()).isEqualTo(1);
        assertThat(issue(IssueState.RECEIVED, 3).suggestTriage(2, LATER)).isEmpty();
        assertThat(issue(IssueState.RECEIVED, 3).suggestTriage(3, LATER)).isEmpty();
        assertThat(issue(IssueState.RECEIVED, 1).suggestTriage(2, LATER)).isEmpty();
    }

    /** A summary moves the version but not the report the suggestion was computed from. */
    @Test
    void aSummaryPublishedFirstDoesNotMakeTheSuggestionLate() {
        SummaryId summary = new SummaryId(UUID.randomUUID());
        Issue shared = Issue.receive(ID, BUILDING, AREA, CREATED).publishSummary(1, summary, LATER);
        assertThat(shared.version()).isEqualTo(2);

        Optional<Issue> next = shared.suggestTriage(1, LATER.plusSeconds(60));

        assertThat(next).isPresent();
        assertThat(next.get().state()).isEqualTo(IssueState.IN_TRIAGE);
        assertThat(next.get().version()).isEqualTo(3);
        assertThat(next.get().publishedSummaryId()).contains(summary);
        assertThat(next.get().category()).isEmpty();
    }

    @Test
    void stateHelpersFollowTheStateTable() {
        assertThat(IssueState.RESOLVED.isOpen()).isFalse();
        assertThat(IssueState.CANCELLED.isOpen()).isFalse();
        assertThat(IssueState.AWAITING_VALIDATION.isOpen()).isTrue();
        assertThat(IssueState.TRIAGED.isDispatchable()).isTrue();
        assertThat(IssueState.IN_COORDINATION.isDispatchable()).isTrue();
        assertThat(IssueState.IN_TRIAGE.isDispatchable()).isFalse();
        assertThat(IssueState.SCHEDULED.isDispatchable()).isFalse();
    }
}
