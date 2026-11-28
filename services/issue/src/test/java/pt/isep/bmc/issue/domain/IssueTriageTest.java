package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.event.IssueTriaged;
import pt.isep.bmc.issue.domain.issue.CriticalFindings;
import pt.isep.bmc.issue.domain.issue.CriticalAlertsV1;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueTriage;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.TriageOutcome;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ForbiddenException;
import pt.isep.bmc.issue.domain.shared.ValidationException;
import pt.isep.bmc.issue.domain.shared.VersionConflictException;

/** Who may triage, in which order the checks run, and what a decision produces (US-02). */
class IssueTriageTest {

    static final Instant NOW = BuildingAccessTest.NOW;
    static final UserId ME = BuildingAccessTest.ME;
    static final BuildingId A = BuildingAccessTest.A;
    static final BuildingId B = BuildingAccessTest.B;
    static final String REASON = "Water near an electrical fitting";

    final Issue received = Issue.receive(IssueTest.ID, A, IssueTest.AREA, IssueTest.CREATED, CriticalFindings.none(CriticalAlertsV1.VERSION));

    static BuildingAccess access(BuildingId building, MembershipRole role) {
        return BuildingAccess.of(ME, List.of(BuildingAccessTest.active(ME, building, role)), NOW);
    }

    TriageOutcome decide(BuildingAccess access, boolean reported, long version, String category, String urgency, String reason) {
        return IssueTriage.decide(access, received, reported, version, category, urgency, reason, 2, "Garage level -1", NOW);
    }

    @Test
    void anAdministratorOfTheBuildingDecidesAndEverythingToStoreIsProduced() {
        TriageOutcome outcome = decide(access(A, MembershipRole.ADMINISTRATOR), false, 1, "plumbing", "high", "  " + REASON + " ");

        assertThat(outcome.issue().state()).isEqualTo(IssueState.TRIAGED);
        assertThat(outcome.issue().version()).isEqualTo(2);
        assertThat(outcome.decision().category()).isEqualTo(IssueCategory.PLUMBING);
        assertThat(outcome.decision().urgency()).isEqualTo(IssueUrgency.HIGH);
        assertThat(outcome.decision().reason().value()).isEqualTo(REASON);
        assertThat(outcome.decision().decidedBy()).isEqualTo(ME);
        assertThat(outcome.decision().resultingVersion()).isEqualTo(2);
        assertThat(outcome.decision().decidedAt()).isEqualTo(NOW);
        assertThat(outcome.decision().toString()).doesNotContain("Water");

        assertThat(outcome.history().type()).isEqualTo(HistoryEntryType.TRIAGE_DECIDED);
        assertThat(outcome.history().visibility()).isEqualTo(HistoryVisibility.PUBLIC);
        assertThat(outcome.history().actorRole()).isEqualTo("administrator");
        assertThat(outcome.history().sequence()).isEqualTo(2);
        assertThat(outcome.history().issueVersion()).isEqualTo(2);

        assertThat(outcome.events()).containsExactly(
                new IssueTriaged(received.id(), A, 2, IssueCategory.PLUMBING, IssueUrgency.HIGH, NOW),
                new IssueStateChanged(received.id(), A, IssueState.TRIAGED, 2, true, Optional.of(IssueCategory.PLUMBING),
                        Optional.of(IssueUrgency.HIGH), "Garage level -1", Optional.empty(), NOW));
    }

    @Test
    void whoeverCannotSeeTheIssueLearnsNothingAboutIt() {
        // Administrator of another building, resident of the same building who did not report, and a stranger.
        for (BuildingAccess access : List.of(
                access(B, MembershipRole.ADMINISTRATOR),
                access(A, MembershipRole.RESIDENT),
                BuildingAccess.of(ME, List.of(), NOW))) {
            assertThatThrownBy(() -> decide(access, false, 1, "plumbing", "high", REASON))
                    .isInstanceOf(AccessDeniedException.class);
            // Not even whether the version or the body would have been accepted.
            assertThatThrownBy(() -> decide(access, false, 99, "nonsense", null, null))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void theResidentWhoReportedItSeesItButMayNotTriage() {
        assertThatThrownBy(() -> decide(access(A, MembershipRole.RESIDENT), true, 1, "plumbing", "high", REASON))
                .isInstanceOfSatisfying(ForbiddenException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
        // A reporter with no membership any more (moved out) is still only a viewer.
        assertThatThrownBy(() -> decide(BuildingAccess.of(ME, List.of(), NOW), true, 1, "plumbing", "high", REASON))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void everyInvalidFieldIsReportedAtOnceBeforeTheVersionIsLookedAt() {
        assertThatThrownBy(() -> decide(access(A, MembershipRole.ADMINISTRATOR), false, 99, "gardening", " ", "x".repeat(501)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations()).containsExactly(
                        new FieldViolation("category", "invalid"),
                        new FieldViolation("urgency", "required"),
                        new FieldViolation("reason", "too_long")));
        assertThatThrownBy(() -> decide(access(A, MembershipRole.ADMINISTRATOR), false, 1, null, "urgent", null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations()).containsExactly(
                        new FieldViolation("category", "required"),
                        new FieldViolation("urgency", "invalid"),
                        new FieldViolation("reason", "required")));
        // Wire values are lower case, exactly.
        assertThatThrownBy(() -> decide(access(A, MembershipRole.ADMINISTRATOR), false, 1, "Plumbing", "HIGH", REASON))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aValidDecisionOnAStaleVersionIsAConflict() {
        assertThatThrownBy(() -> decide(access(A, MembershipRole.ADMINISTRATOR), false, 3, "plumbing", "high", REASON))
                .isInstanceOf(VersionConflictException.class);
    }
}
