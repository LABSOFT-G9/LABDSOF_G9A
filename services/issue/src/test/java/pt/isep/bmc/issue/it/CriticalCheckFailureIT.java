package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase.Result;
import pt.isep.bmc.issue.domain.CriticalCheckTest;
import pt.isep.bmc.issue.domain.issue.CriticalRules;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;

/**
 * US-14 with a critical rule set that always fails (the "rules failure"
 * demonstration): reporting, viewing and manual triage still work, the check
 * is shown as not done, and the failure is in the log without the report.
 */
@Import(CriticalCheckFailureIT.FailingRules.class)
@ExtendWith(OutputCaptureExtension.class)
class CriticalCheckFailureIT extends IntegrationTestSupport {

    static final String DANGER = "Unmistakable words: there is smoke in the garage and a smell of gas.";

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingRules {

        /** Fails with a message that quotes the report, as a careless rule set could. */
        @Bean
        @Primary
        CriticalRules failingCriticalRules() {
            return CriticalCheckTest.failing();
        }
    }

    @Autowired
    CompletePendingCriticalChecksUseCase pendingChecks;

    @Test
    @SuppressWarnings("unchecked")
    void theReportIsReceivedViewableAndTriageableAndTheCheckIsShownAsNotDone(CapturedOutput output) {
        Response created = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                report(BUILDING_A, AREA_A_GARAGE, DANGER));

        // The receipt is the usual one: the failure neither refuses nor changes it.
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json()).containsOnlyKeys("reportId", "issueId", "state", "version", "submittedAt")
                .containsEntry("state", "received").containsEntry("version", 1);
        String issueId = (String) created.json().get("issueId");
        String reportId = (String) created.json().get("reportId");

        // Viewable by its author and by the administrator.
        assertThat(api.get("/reports/" + reportId, tokens.token(RESIDENT_A1)).json()).containsEntry("text", DANGER);
        Map<String, Object> issue = api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json();
        Map<String, Object> check = (Map<String, Object>) issue.get("criticalCheck");
        // Not checked: never a completed check with nothing found.
        assertThat(check).containsEntry("status", "pending").containsEntry("rulesVersion", null);
        assertThat((List<?>) check.get("signals")).isEmpty();
        assertThat(count("issue_critical_signals")).isZero();

        // Triage was still requested, and manual triage works.
        assertThat(jdbc.sql("SELECT event_type FROM outbox").query(String.class).list())
                .containsExactlyInAnyOrder("ReportSubmitted", "TriageRequested");
        assertThat(triage(ADMIN_A, issueId, 1, "electrical", "critical", "Smoke reported: treat as critical.")
                .status()).isEqualTo(200);

        // Another run fails again: still pending, the decision untouched.
        assertThat(pendingChecks.completePending()).isEqualTo(new Result(0, 1, 0));
        Map<String, Object> after = api.get("/issues/" + issueId, tokens.token(ADMIN_A)).json();
        assertThat(after).containsEntry("state", "triaged").containsEntry("version", 2);
        assertThat((Map<String, Object>) after.get("criticalCheck")).containsEntry("status", "pending");

        // Visible to operators: the issue and the exception class. Never the report.
        assertThat(output.getAll())
                .contains("Critical check did not run for issue " + issueId + ": java.lang.IllegalArgumentException")
                .doesNotContain("Unmistakable words", "smoke in the garage", "smell of gas");
    }
}
