package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pt.isep.bmc.issue.application.command.RecordTriageSuggestionCommand;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase.Result;
import pt.isep.bmc.issue.application.port.in.RecordTriageSuggestionUseCase;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.SuggestionInput;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;

/**
 * US-14-02 on real MySQL: Issue's own critical check runs with the report,
 * its alerts are shown to the building's administrators only, and nothing
 * that happens later (a suggestion, a decision, a publication, another run
 * of the check) removes or lowers one.
 */
class CriticalAlertIT extends IntegrationTestSupport {

    static final String DANGER = "There is smoke in the garage and a strong smell of gas near the door.";

    @Autowired
    CompletePendingCriticalChecksUseCase pendingChecks;

    @Autowired
    RecordTriageSuggestionUseCase suggestions;

    String submit(String text) {
        Response response = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, text));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json()).containsEntry("state", "received").containsEntry("version", 1);
        return (String) response.json().get("issueId");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> checkOf(Map<String, Object> issue) {
        return (Map<String, Object>) issue.get("criticalCheck");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> signalsOf(Map<String, Object> issue) {
        return (List<Map<String, Object>>) checkOf(issue).get("signals");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> itemsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    List<String> codes(String issueId) {
        return signalsOf(api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json()).stream()
                .map(signal -> (String) signal.get("code")).toList();
    }

    @Test
    void aDangerousReportIsReceivedAsUsualAndItsAdministratorsSeeTheAlert() {
        String issueId = submit(DANGER);

        Response read = api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        assertThat(read.status()).isEqualTo(200);
        // The alert is not a decision: the issue is received at version 1, nothing decided, no urgency set.
        assertThat(read.header("ETag")).isEqualTo("\"1\"");
        assertThat(read.json()).containsEntry("state", "received").containsEntry("version", 1)
                .containsEntry("triage", null);
        assertThat(checkOf(read.json())).containsEntry("status", "completed")
                .containsEntry("rulesVersion", "critical-alerts-v1");
        assertThat(signalsOf(read.json())).extracting(signal -> signal.get("code"))
                .containsExactly("smoke_or_fire", "gas_smell");
        assertThat(signalsOf(read.json())).allSatisfy(signal -> assertThat((String) signal.get("raisedAt"))
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"));
        // A code, never the words: the report text is not in the issue.
        assertThat(read.body()).doesNotContain("smoke in the garage", "smell of gas");

        assertThat(itemsOf(api.get("/issues", tokens.token(ADMIN_A)).json())).singleElement().satisfies(item -> {
            assertThat(checkOf(item)).containsEntry("status", "completed");
            assertThat(signalsOf(item)).extracting(signal -> signal.get("code"))
                    .containsExactly("smoke_or_fire", "gas_smell");
        });

        // Triage is still requested: the check replaces neither the suggestion nor the decision.
        assertThat(jdbc.sql("SELECT event_type FROM outbox ORDER BY created_at, event_type").query(String.class).list())
                .containsExactlyInAnyOrder("ReportSubmitted", "TriageRequested");
        assertThat(jdbc.sql("SELECT urgency FROM issues WHERE id = :id").param("id", issueId).query().singleRow())
                .containsEntry("urgency", null);
    }

    @Test
    void anOrdinaryReportIsCheckedAndHasNoSignal() {
        String issueId = submit(TEXT);

        Map<String, Object> issue = api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json();
        assertThat(checkOf(issue)).containsEntry("status", "completed")
                .containsEntry("rulesVersion", "critical-alerts-v1");
        assertThat(signalsOf(issue)).isEmpty();
        assertThat(count("issue_critical_signals")).isZero();
    }

    @Test
    void theHistoryShowsOneInternalEntryPerAlertWithItsCode() {
        String issueId = submit(DANGER);

        List<Map<String, Object>> history =
                itemsOf(api.get("/issues/" + issueId + "/history", tokens.token(ADMIN_A)).json());

        assertThat(history).extracting(entry -> entry.get("type"))
                .containsExactly("report_received", "critical_alert_raised", "critical_alert_raised");
        assertThat(history).extracting(entry -> entry.get("code")).containsExactly(null, "smoke_or_fire", "gas_smell");
        assertThat(history.subList(1, 3)).allSatisfy(entry -> assertThat(entry)
                .containsEntry("visibility", "internal").containsEntry("actorRole", "system"));
    }

    @Test
    void whoeverDoesNotAdministerTheBuildingNeverSeesTheCheckOrItsAlerts() {
        String issueId = submit(DANGER);

        // The reporter follows the issue, and sees no critical check at all.
        Response reporter = api.get("/issues/" + issueId, tokens.token(RESIDENT_A1));
        assertThat(reporter.status()).isEqualTo(200);
        assertThat(reporter.json()).containsEntry("criticalCheck", null);
        assertThat(reporter.body()).doesNotContain("smoke_or_fire", "gas_smell", "critical-alerts-v1");
        assertThat(itemsOf(api.get("/issues", tokens.token(RESIDENT_A1)).json()))
                .singleElement().satisfies(item -> assertThat(item).containsEntry("criticalCheck", null));
        Response reporterHistory = api.get("/issues/" + issueId + "/history", tokens.token(RESIDENT_A1));
        assertThat(itemsOf(reporterHistory.json())).extracting(entry -> entry.get("type"))
                .containsExactly("report_received");
        assertThat(reporterHistory.body()).doesNotContain("critical_alert_raised", "smoke_or_fire");

        // Once shared, the other residents of the building see the issue, and still no alert.
        assertThat(publish(ADMIN_A, issueId, 1, "Something in the garage is being looked at.").status()).isEqualTo(200);
        Response neighbour = api.get("/issues/" + issueId, tokens.token(RESIDENT_A2));
        assertThat(neighbour.status()).isEqualTo(200);
        assertThat(neighbour.json()).containsEntry("criticalCheck", null);
        assertThat(api.get("/issues/" + issueId + "/history", tokens.token(RESIDENT_A2)).body())
                .doesNotContain("critical_alert_raised", "gas_smell");

        assertThat(api.get("/issues/" + issueId, tokens.token(ADMIN_B)).status()).isEqualTo(404);
        assertThat(api.get("/issues/" + issueId, tokens.token(CONTRACTOR)).status()).isEqualTo(404);
    }

    @Test
    void noSuggestionDecisionOrPublicationRemovesOrLowersAnAlert() {
        String issueId = submit(DANGER);

        // The AI disagrees: low urgency, nothing to review.
        IssueId issue = new IssueId(UUID.fromString(issueId));
        suggestions.record(new RecordTriageSuggestionCommand(issue, new SuggestionInput(UUID.randomUUID(), 1,
                "cleaning", "low", "Looks like a cleaning matter.", false, "ai", "model-x-1", "triage-v1", null,
                "triage-output-v1", null), "corr-critical-it"));
        assertThat(codes(issueId)).containsExactly("smoke_or_fire", "gas_smell");

        // A late suggestion, delivered twice, from the rules fallback: nothing changes either.
        SuggestionInput late = new SuggestionInput(UUID.randomUUID(), 1, "other", "low", "Nothing urgent.", false,
                "fallback", null, null, "rules-v1", null, "timeout");
        for (int i = 0; i < 2; i++) {
            suggestions.record(new RecordTriageSuggestionCommand(issue, late, "corr-critical-it"));
        }
        assertThat(codes(issueId)).containsExactly("smoke_or_fire", "gas_smell");

        // The administrator decides "low": the decision is theirs, and the alert stays in view.
        assertThat(triage(ADMIN_A, issueId, 2, "cleaning", "low", "Checked on site, it was steam.").status())
                .isEqualTo(200);
        assertThat(publish(ADMIN_A, issueId, 3, "It was steam from the laundry vent.").status()).isEqualTo(200);

        Map<String, Object> read = api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json();
        assertThat(read).containsEntry("state", "triaged");
        assertThat(checkOf(read)).containsEntry("status", "completed");
        assertThat(signalsOf(read)).extracting(signal -> signal.get("code"))
                .containsExactly("smoke_or_fire", "gas_smell");
        assertThat(count("issue_critical_signals")).isEqualTo(2);

        // Another run of the check has nothing to do, and adds nothing.
        assertThat(pendingChecks.completePending()).isEqualTo(new Result(0, 0, 0));
        assertThat(count("issue_critical_signals")).isEqualTo(2);
    }

    @Test
    void aCheckLeftPendingIsShownAsNotCheckedAndIsCompletedLaterWithoutTouchingTheDecision() {
        String issueId = submit(DANGER);
        // As an issue stored before the check existed, or when the rule set failed: pending, no signal.
        jdbc.sql("DELETE FROM issue_critical_signals").update();
        jdbc.sql("DELETE FROM issue_history WHERE type = 'critical_alert_raised'").update();
        jdbc.sql("UPDATE issues SET critical_check_status = 'pending', critical_rules_version = NULL").update();

        Map<String, Object> pending = api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json();
        // Never "checked, nothing found": pending, and no rule set named.
        assertThat(checkOf(pending)).containsEntry("status", "pending").containsEntry("rulesVersion", null);
        assertThat(signalsOf(pending)).isEmpty();

        // The report is still viewable and the issue manually triageable while the check is pending.
        assertThat(triage(ADMIN_A, issueId, 1, "electrical", "normal", "Looks like a socket.").status()).isEqualTo(200);

        assertThat(pendingChecks.completePending()).isEqualTo(new Result(1, 0, 2));

        Response read = api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        // The late alert moves the version, so a decision made on a page without it is refused.
        assertThat(read.header("ETag")).isEqualTo("\"3\"");
        assertThat(read.json()).containsEntry("state", "triaged").containsEntry("version", 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> triage = (Map<String, Object>) read.json().get("triage");
        assertThat(triage).containsEntry("category", "electrical").containsEntry("urgency", "normal");
        assertThat(checkOf(read.json())).containsEntry("status", "completed");
        assertThat(codes(issueId)).containsExactly("smoke_or_fire", "gas_smell");
        assertThat(triage(ADMIN_A, issueId, 2, "electrical", "low", "Stale page.").status()).isEqualTo(409);

        List<Map<String, Object>> history =
                itemsOf(api.get("/issues/" + issueId + "/history", tokens.token(ADMIN_A)).json());
        assertThat(history).extracting(entry -> entry.get("type")).containsExactly(
                "report_received", "triage_decided", "critical_alert_raised", "critical_alert_raised");
        assertThat(history).extracting(entry -> entry.get("sequence")).containsExactly(1, 2, 3, 4);
        // No event for the late alert: the state did not change.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE aggregate_version = 3").query(Long.class).single())
                .isZero();
    }

    @Test
    void theDatabaseRefusesACheckStoredAsCompletedWithoutARuleSetAndASignalRaisedTwice() {
        String issueId = submit(DANGER);

        assertThat(statementFails("UPDATE issues SET critical_rules_version = NULL")).isTrue();
        assertThat(statementFails("UPDATE issues SET critical_check_status = 'pending'")).isTrue();
        assertThat(statementFails("UPDATE issues SET critical_check_status = 'cleared'")).isTrue();
        assertThat(statementFails("INSERT INTO issue_critical_signals (issue_id, code, rules_version,"
                + " history_sequence, raised_at) VALUES ('" + issueId + "', 'gas_smell', 'critical-alerts-v1', 9, NOW(6))"))
                .isTrue();
    }

    private boolean statementFails(String sql) {
        try {
            jdbc.sql(sql).update();
            return false;
        } catch (org.springframework.dao.DataAccessException e) {
            return true;
        }
    }
}
