package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.event.IssueSummaryPublished;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueSharing;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.PublicationOutcome;
import pt.isep.bmc.issue.domain.issue.SummaryId;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ForbiddenException;
import pt.isep.bmc.issue.domain.shared.NotPublishedException;
import pt.isep.bmc.issue.domain.shared.ValidationException;
import pt.isep.bmc.issue.domain.shared.VersionConflictException;

/** Who may publish, confirm impact and subscribe, in which order the checks run, and what a publication produces (US-04). */
class IssueSharingTest {

    static final Instant NOW = BuildingAccessTest.NOW;
    static final UserId ME = BuildingAccessTest.ME;
    static final BuildingId A = BuildingAccessTest.A;
    static final BuildingId B = BuildingAccessTest.B;
    static final String SUMMARY = "The garage door on level -1 does not close fully.";
    static final SummaryId SUMMARY_ID = new SummaryId(UUID.fromString("0199f2a1-0000-7000-8000-000000007001"));

    final Issue received = Issue.receive(IssueTest.ID, A, IssueTest.AREA, IssueTest.CREATED);
    final Issue published = received.publishSummary(1, SUMMARY_ID, NOW);

    static BuildingAccess access(BuildingId building, MembershipRole role) {
        return BuildingAccess.of(ME, List.of(BuildingAccessTest.active(ME, building, role)), NOW);
    }

    static BuildingAccess stranger() {
        return BuildingAccess.of(ME, List.of(), NOW);
    }

    PublicationOutcome publish(BuildingAccess access, Issue issue, boolean reported, long version, String summary) {
        return IssueSharing.publish(access, issue, reported, version, summary, SUMMARY_ID, 2, "Garage level -1", NOW);
    }

    @Test
    void anAdministratorOfTheBuildingPublishesAndEverythingToStoreIsProduced() {
        PublicationOutcome outcome =
                publish(access(A, MembershipRole.ADMINISTRATOR), received, false, 1, "  " + SUMMARY + " \n");

        // Only the version moves: publishing decides nothing about the fault.
        assertThat(outcome.issue().version()).isEqualTo(2);
        assertThat(outcome.issue().state()).isEqualTo(IssueState.RECEIVED);
        assertThat(outcome.issue().category()).isEmpty();
        assertThat(outcome.issue().publishedSummaryId()).contains(SUMMARY_ID);
        assertThat(outcome.issue().isPublished()).isTrue();

        assertThat(outcome.summary().id()).isEqualTo(SUMMARY_ID);
        assertThat(outcome.summary().text().value()).isEqualTo(SUMMARY);
        assertThat(outcome.summary().publishedBy()).isEqualTo(ME);
        assertThat(outcome.summary().resultingVersion()).isEqualTo(2);
        assertThat(outcome.summary().publishedAt()).isEqualTo(NOW);
        assertThat(outcome.summary().toString()).doesNotContain(ME.value());

        assertThat(outcome.history().type()).isEqualTo(HistoryEntryType.SUMMARY_PUBLISHED);
        assertThat(outcome.history().visibility()).isEqualTo(HistoryVisibility.PUBLIC);
        assertThat(outcome.history().actorRole()).isEqualTo("administrator");
        assertThat(outcome.history().sequence()).isEqualTo(2);
        assertThat(outcome.history().issueVersion()).isEqualTo(2);

        // The events carry the summary by reference, never its text.
        assertThat(outcome.events()).containsExactly(
                new IssueSummaryPublished(received.id(), A, 2, SUMMARY_ID, NOW),
                new IssueStateChanged(received.id(), A, IssueState.RECEIVED, 2, false, Optional.empty(),
                        Optional.empty(), "Garage level -1", Optional.of(SUMMARY_ID), NOW));
    }

    @Test
    void publishingAgainReplacesTheSummaryAndKeepsTheDecision() {
        Issue triaged = published.decideTriage(2, IssueCategory.PLUMBING, IssueUrgency.HIGH, NOW);
        SummaryId second = new SummaryId(UUID.fromString("0199f2a1-0000-7000-8000-000000007002"));

        Issue republished = triaged.publishSummary(3, second, NOW);

        assertThat(triaged.publishedSummaryId()).as("a decision keeps the summary").contains(SUMMARY_ID);
        assertThat(republished.publishedSummaryId()).contains(second);
        assertThat(republished.version()).isEqualTo(4);
        assertThat(republished.state()).isEqualTo(IssueState.TRIAGED);
        assertThat(republished.category()).contains(IssueCategory.PLUMBING);
        assertThat(republished.urgency()).contains(IssueUrgency.HIGH);
    }

    @Test
    void whoeverCannotSeeTheIssueLearnsNothingAboutIt() {
        // Before publication: another building's administrator, a resident who did not report, a stranger.
        for (BuildingAccess access : List.of(
                access(B, MembershipRole.ADMINISTRATOR), access(A, MembershipRole.RESIDENT), stranger())) {
            assertThatThrownBy(() -> publish(access, received, false, 99, "x"))
                    .as("not found comes before the text and the version")
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> IssueSharing.requireMayChangeImpact(access, received, false))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> IssueSharing.requireVisible(access, received, false))
                    .isInstanceOf(AccessDeniedException.class);
        }
        // After publication the building's residents see it; other buildings and strangers still do not.
        for (BuildingAccess access : List.of(access(B, MembershipRole.RESIDENT), stranger())) {
            assertThatThrownBy(() -> IssueSharing.requireVisible(access, published, false))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> IssueSharing.requireMayChangeImpact(access, published, false))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void aResidentWhoCanSeeTheIssueMayNotPublish() {
        // The reporter before publication, and any resident of the building after it.
        assertThatThrownBy(() -> publish(access(A, MembershipRole.RESIDENT), received, true, 99, "x"))
                .as("forbidden comes before the text and the version")
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> publish(access(A, MembershipRole.RESIDENT), published, false, 2, SUMMARY))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anInvalidSummaryIsRefusedBeforeTheVersionIsLookedAt() {
        BuildingAccess administrator = access(A, MembershipRole.ADMINISTRATOR);

        assertThatThrownBy(() -> publish(administrator, received, false, 99, "too short"))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("summary", "too_short")));
        assertThatThrownBy(() -> publish(administrator, received, false, 1, null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("summary", "required")));
    }

    @Test
    void aStaleVersionChangesNothing() {
        assertThatThrownBy(() -> publish(access(A, MembershipRole.ADMINISTRATOR), published, false, 1, SUMMARY))
                .isInstanceOfSatisfying(VersionConflictException.class, e -> {
                    assertThat(e.currentVersion()).isEqualTo(2);
                    assertThat(e.currentState()).isEqualTo("received");
                });
    }

    @Test
    void onlyResidentsOfTheBuildingConfirmImpactAndOnlyOnceItIsPublished() {
        BuildingAccess resident = access(A, MembershipRole.RESIDENT);

        // Any resident of the building, the reporter included.
        assertThatCode(() -> IssueSharing.requireMayChangeImpact(resident, published, false)).doesNotThrowAnyException();
        assertThatCode(() -> IssueSharing.requireMayChangeImpact(resident, published, true)).doesNotThrowAnyException();

        // The reporter sees the issue before publication, but there is nothing shared to confirm yet.
        assertThatThrownBy(() -> IssueSharing.requireMayChangeImpact(resident, received, true))
                .isInstanceOf(NotPublishedException.class);

        // Administrators see the count; they do not contribute to it.
        BuildingAccess administrator = access(A, MembershipRole.ADMINISTRATOR);
        assertThatThrownBy(() -> IssueSharing.requireMayChangeImpact(administrator, published, false))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> IssueSharing.requireMayChangeImpact(administrator, received, false))
                .as("forbidden comes before not published")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aPersonWhoIsBothResidentAndAdministratorConfirmsAsAResident() {
        BuildingAccess both = BuildingAccess.of(ME, List.of(
                BuildingAccessTest.active(ME, A, MembershipRole.RESIDENT),
                BuildingAccessTest.active(ME, A, MembershipRole.ADMINISTRATOR)), NOW);

        assertThatCode(() -> IssueSharing.requireMayChangeImpact(both, published, false)).doesNotThrowAnyException();
    }

    @Test
    void anyoneWhoSeesTheIssueMaySubscribe() {
        assertThatCode(() -> IssueSharing.requireVisible(access(A, MembershipRole.ADMINISTRATOR), received, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> IssueSharing.requireVisible(access(A, MembershipRole.RESIDENT), received, true))
                .as("the reporter, before publication").doesNotThrowAnyException();
        assertThatCode(() -> IssueSharing.requireVisible(access(A, MembershipRole.RESIDENT), published, false))
                .as("any resident, after publication").doesNotThrowAnyException();
    }

    @Test
    void aPublishedSummaryWidensVisibilityToTheBuildingOnly() {
        BuildingAccess resident = access(A, MembershipRole.RESIDENT);
        assertThat(resident.canViewIssue(A, false, false)).isFalse();
        assertThat(resident.canViewIssue(A, false, true)).isTrue();
        assertThat(resident.canViewIssue(B, false, true)).isFalse();
        assertThat(resident.isResidentOf(A)).isTrue();
        assertThat(access(A, MembershipRole.ADMINISTRATOR).isResidentOf(A)).isFalse();
        assertThat(resident.memberBuildings()).containsExactly(A);
    }
}
