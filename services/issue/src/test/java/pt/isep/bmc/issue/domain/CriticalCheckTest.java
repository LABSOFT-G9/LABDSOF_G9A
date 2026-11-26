package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pt.isep.bmc.issue.domain.issue.CriticalAlertsV1;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.CriticalFindings;
import pt.isep.bmc.issue.domain.issue.CriticalOutcome;
import pt.isep.bmc.issue.domain.issue.CriticalRules;
import pt.isep.bmc.issue.domain.issue.CriticalSignal;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.HistoryVisibility;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.report.ReportText;

/** US-14-02: what a run of the critical check does to an issue, and what it never does. */
public class CriticalCheckTest {

    static final Instant LATER = IssueTest.LATER;
    static final ReportText SMOKE_AND_GAS = ReportText.of("There is smoke in the garage and a smell of gas.");
    static final ReportText ORDINARY = ReportText.of("The stair light on floor 2 has been out for a week.");
    static final String PRIVATE_WORDS = "smoke in the garage";

    /** A rule set that fails, with a message that quotes the report, as a careless one could. */
    public static CriticalRules failing() {
        return rules("critical-alerts-v1", text -> {
            throw new IllegalArgumentException("cannot read: " + text.value());
        });
    }

    public static CriticalRules rules(String version, java.util.function.Function<ReportText, List<String>> evaluate) {
        return new CriticalRules() {
            @Override
            public String version() {
                return version;
            }

            @Override
            public List<String> evaluate(ReportText text) {
                return evaluate.apply(text);
            }
        };
    }

    static Issue pending(IssueState state, long version) {
        return IssueTest.issue(state, version);
    }

    @Test
    void aCheckThatRanNamesItsRuleSetAndTheCodesThatMatched() {
        CriticalFindings findings = CriticalFindings.of(new CriticalAlertsV1(), SMOKE_AND_GAS);

        assertThat(findings.completed()).isTrue();
        assertThat(findings.rulesVersion()).contains("critical-alerts-v1");
        assertThat(findings.codes()).containsExactly("smoke_or_fire", "gas_smell");
        assertThat(findings.failure()).isEmpty();
    }

    @Test
    void aCheckThatMatchedNothingStillRan() {
        CriticalFindings findings = CriticalFindings.of(new CriticalAlertsV1(), ORDINARY);

        assertThat(findings.completed()).isTrue();
        assertThat(findings.codes()).isEmpty();
    }

    @Test
    void aRuleSetThatFailsDidNotCheckAndItsFailureNeverQuotesTheReport() {
        CriticalFindings findings = CriticalFindings.of(failing(), SMOKE_AND_GAS);

        assertThat(findings.completed()).isFalse();
        assertThat(findings.rulesVersion()).isEmpty();
        assertThat(findings.codes()).isEmpty();
        assertThat(findings.failure()).contains("java.lang.IllegalArgumentException");
        assertThat(findings.failure().orElseThrow()).doesNotContain(PRIVATE_WORDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Smoke", "has space", "", "1starts_with_digit", "the report mentions smoke in the garage and more"})
    void aRuleSetThatAnswersSomethingThatIsNotACodeDidNotCheck(String notACode) {
        CriticalFindings findings = CriticalFindings.of(rules("critical-alerts-v1", text -> List.of(notACode)), ORDINARY);

        assertThat(findings.completed()).isFalse();
        assertThat(findings.codes()).isEmpty();
    }

    @Test
    void aRuleSetWithoutAVersionDidNotCheck() {
        assertThat(CriticalFindings.of(rules(" ", text -> List.of()), ORDINARY).completed()).isFalse();
        assertThat(CriticalFindings.of(rules(null, text -> List.of()), ORDINARY).completed()).isFalse();
    }

    @Test
    void aCodeAnsweredTwiceIsOneSignal() {
        CriticalFindings findings =
                CriticalFindings.of(rules("v", text -> List.of("gas_smell", "smoke_or_fire", "gas_smell")), ORDINARY);

        assertThat(findings.codes()).containsExactly("gas_smell", "smoke_or_fire");
    }

    @Test
    void checksOfSeveralReportsAddUpAndOneFailureMeansNotChecked() {
        CriticalFindings smoke = CriticalFindings.of(rules("v", text -> List.of("smoke_or_fire")), ORDINARY);
        CriticalFindings gas = CriticalFindings.of(rules("v", text -> List.of("gas_smell", "smoke_or_fire")), ORDINARY);
        CriticalFindings failed = CriticalFindings.of(failing(), ORDINARY);

        assertThat(CriticalFindings.none("v").and(smoke).and(gas).codes()).containsExactly("smoke_or_fire", "gas_smell");
        assertThat(smoke.and(failed).completed()).isFalse();
        assertThat(failed.and(smoke).completed()).isFalse();
        assertThat(smoke.and(failed).codes()).isEmpty();
    }

    @Test
    void aNewIssueWhoseCheckRanIsCompletedAndOneWhoseCheckFailedIsPending() {
        Issue checked = Issue.receive(IssueTest.ID, IssueTest.BUILDING, IssueTest.AREA, IssueTest.CREATED,
                CriticalFindings.of(new CriticalAlertsV1(), SMOKE_AND_GAS));
        Issue unchecked = Issue.receive(IssueTest.ID, IssueTest.BUILDING, IssueTest.AREA, IssueTest.CREATED,
                CriticalFindings.of(failing(), SMOKE_AND_GAS));

        assertThat(checked.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED);
        assertThat(checked.criticalRulesVersion()).contains("critical-alerts-v1");
        assertThat(checked.version()).isEqualTo(1);
        assertThat(unchecked.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.PENDING);
        assertThat(unchecked.criticalRulesVersion()).isEmpty();
        // Either way it is an ordinary new issue: received, nothing decided.
        assertThat(unchecked.state()).isEqualTo(IssueState.RECEIVED);
        assertThat(unchecked.version()).isEqualTo(1);
    }

    @Test
    void aCompletedCheckAlwaysNamesItsRuleSetAndAPendingOneNever() {
        assertThatThrownBy(() -> Issue.restore(IssueTest.ID, IssueTest.BUILDING, IssueTest.AREA, IssueState.RECEIVED, 1,
                null, null, CriticalCheckStatus.COMPLETED, null, null, IssueTest.CREATED, IssueTest.CREATED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Issue.restore(IssueTest.ID, IssueTest.BUILDING, IssueTest.AREA, IssueState.RECEIVED, 1,
                null, null, CriticalCheckStatus.PENDING, "critical-alerts-v1", null, IssueTest.CREATED,
                IssueTest.CREATED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSignalPerCodeEachWithItsOwnInternalHistoryEntry() {
        CriticalFindings findings = CriticalFindings.of(new CriticalAlertsV1(), SMOKE_AND_GAS);
        Issue issue = Issue.receive(IssueTest.ID, IssueTest.BUILDING, IssueTest.AREA, IssueTest.CREATED, findings);

        CriticalOutcome outcome = CriticalOutcome.of(issue, findings, 2, Set.of(), IssueTest.CREATED);

        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.signals()).extracting(CriticalSignal::code).containsExactly("smoke_or_fire", "gas_smell");
        assertThat(outcome.signals()).extracting(CriticalSignal::historySequence).containsExactly(2, 3);
        assertThat(outcome.signals()).allSatisfy(signal -> {
            assertThat(signal.rulesVersion()).isEqualTo("critical-alerts-v1");
            assertThat(signal.raisedAt()).isEqualTo(IssueTest.CREATED);
        });
        assertThat(outcome.history()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo(HistoryEntryType.CRITICAL_ALERT_RAISED);
            assertThat(entry.visibility()).isEqualTo(HistoryVisibility.INTERNAL);
            assertThat(entry.actorRole()).isEqualTo("system");
            assertThat(entry.issueVersion()).isEqualTo(1);
        });
        assertThat(outcome.history()).extracting(entry -> entry.sequence()).containsExactly(2, 3);
        // A signal holds a code, never the words that matched.
        assertThat(outcome.toString()).doesNotContain(PRIVATE_WORDS);
    }

    @Test
    void aSignalTheIssueAlreadyHasIsNotRaisedAgain() {
        CriticalFindings findings = CriticalFindings.of(new CriticalAlertsV1(), SMOKE_AND_GAS);
        Issue issue = Issue.receive(IssueTest.ID, IssueTest.BUILDING, IssueTest.AREA, IssueTest.CREATED, findings);

        CriticalOutcome outcome = CriticalOutcome.of(issue, findings, 5, Set.of("smoke_or_fire"), LATER);

        assertThat(outcome.signals()).extracting(CriticalSignal::code).containsExactly("gas_smell");
        assertThat(outcome.signals()).extracting(CriticalSignal::historySequence).containsExactly(5);
    }

    @Test
    void aFailedCheckRaisesNothingAndNamesTheFailure() {
        CriticalFindings findings = CriticalFindings.of(failing(), SMOKE_AND_GAS);
        Issue issue = Issue.receive(IssueTest.ID, IssueTest.BUILDING, IssueTest.AREA, IssueTest.CREATED, findings);

        CriticalOutcome outcome = CriticalOutcome.of(issue, findings, 2, Set.of(), IssueTest.CREATED);

        assertThat(outcome.signals()).isEmpty();
        assertThat(outcome.history()).isEmpty();
        assertThat(outcome.failure()).contains("java.lang.IllegalArgumentException");
    }

    @ParameterizedTest
    @EnumSource(IssueState.class)
    void aLateAlertCompletesTheCheckAndMovesTheVersionInAnyStateWithoutTouchingTheDecision(IssueState state) {
        Issue before = pending(state, 4);
        CriticalFindings findings = CriticalFindings.of(new CriticalAlertsV1(), SMOKE_AND_GAS);

        Issue after = before.completeCriticalCheck(findings, true, LATER).orElseThrow();

        assertThat(after.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED);
        assertThat(after.criticalRulesVersion()).contains("critical-alerts-v1");
        // A decision made on the page that did not show the alert is then refused as a conflict.
        assertThat(after.version()).isEqualTo(5);
        assertThat(after.updatedAt()).isEqualTo(LATER);
        assertThat(after.state()).isEqualTo(state);
        assertThat(after.category()).isEqualTo(before.category());
        assertThat(after.urgency()).isEqualTo(before.urgency());
    }

    @Test
    void aLateCheckThatFindsNothingCompletesWithoutMovingTheVersion() {
        Issue before = pending(IssueState.TRIAGED, 4);

        Issue after = before.completeCriticalCheck(CriticalFindings.none("critical-alerts-v1"), false, LATER)
                .orElseThrow();

        assertThat(after.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED);
        assertThat(after.version()).isEqualTo(4);
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(after.category()).contains(IssueCategory.OTHER);
        assertThat(after.urgency()).contains(IssueUrgency.LOW);
    }

    @Test
    void aCheckThatDidNotRunOrAnIssueAlreadyCheckedChangesNothing() {
        Issue stillPending = pending(IssueState.RECEIVED, 1);
        assertThat(stillPending.completeCriticalCheck(CriticalFindings.of(failing(), ORDINARY), false, LATER)).isEmpty();

        Issue checked = stillPending.completeCriticalCheck(CriticalFindings.none("critical-alerts-v1"), false, LATER)
                .orElseThrow();
        Optional<Issue> again = checked.completeCriticalCheck(
                CriticalFindings.of(new CriticalAlertsV1(), SMOKE_AND_GAS), true, LATER);
        assertThat(again).isEmpty();
    }

    @Test
    void noOtherChangeToAnIssueTouchesItsCheck() {
        Issue checked = Issue.receive(IssueTest.ID, IssueTest.BUILDING, IssueTest.AREA, IssueTest.CREATED,
                CriticalFindings.of(new CriticalAlertsV1(), SMOKE_AND_GAS));

        Issue suggested = checked.suggestTriage(1, LATER).orElseThrow();
        Issue decided = suggested.decideTriage(2, IssueCategory.OTHER, IssueUrgency.LOW, LATER);
        Issue shared = decided.publishSummary(3, new pt.isep.bmc.issue.domain.issue.SummaryId(UUID.randomUUID()), LATER);

        assertThat(List.of(suggested, decided, shared)).allSatisfy(issue -> {
            assertThat(issue.criticalCheckStatus()).isEqualTo(CriticalCheckStatus.COMPLETED);
            assertThat(issue.criticalRulesVersion()).contains("critical-alerts-v1");
        });
    }

    @Test
    void aSignalRefusesAnythingThatIsNotARuleCode() {
        assertThatThrownBy(() -> new CriticalSignal(IssueTest.ID, "Smoke in the garage", "v", 2, LATER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CriticalSignal(IssueTest.ID, "gas_smell", "v", 0, LATER))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
