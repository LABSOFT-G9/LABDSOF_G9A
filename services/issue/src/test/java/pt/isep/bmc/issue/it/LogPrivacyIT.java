package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import pt.isep.bmc.issue.application.command.RecordTriageSuggestionCommand;
import pt.isep.bmc.issue.application.command.SuggestionResult;
import pt.isep.bmc.issue.application.port.in.RecordTriageSuggestionUseCase;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.SuggestionInput;
import pt.isep.bmc.issue.domain.shared.ValidationException;
import pt.isep.bmc.issue.support.IntegrationTestSupport;

/**
 * US-01: private text stays out of logs, even with DEBUG logging of the web,
 * JDBC and Hibernate layers (which print request/response objects and SQL).
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.pt.isep.bmc=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG",
    "logging.level.org.hibernate.SQL=DEBUG"
})
class LogPrivacyIT extends IntegrationTestSupport {

    @Autowired
    RecordTriageSuggestionUseCase suggestions;

    static final String MARKER = "PrivateMarker Flat 2B tenant Jane Synthetic";

    @Test
    void reportTextNeverReachesTheLogs(CapturedOutput output) {
        String text = MARKER + " says water leaks from the garage ceiling.";
        String token = tokens.token(RESIDENT_A1);
        String key = UUID.randomUUID().toString();
        var created = api.post("/reports", token, key, report(BUILDING_A, AREA_A_GARAGE, text));
        assertThat(created.status()).isEqualTo(201);
        api.post("/reports", token, key, report(BUILDING_A, AREA_A_GARAGE, text));          // replay
        api.post("/reports", token, key, report(BUILDING_A, AREA_A_HALL, text));            // 422
        api.post("/reports", token, UUID.randomUUID().toString(), report(BUILDING_A, AREA_B_GARAGE, text)); // 400
        api.post("/reports", token, UUID.randomUUID().toString(), report(BUILDING_A, AREA_A_GARAGE, text + "x".repeat(4_000)));
        api.post("/reports", tokens.token(RESIDENT_B1), UUID.randomUUID().toString(), report(BUILDING_A, AREA_A_GARAGE, text));
        api.get("/reports/" + created.json().get("reportId"), token);
        api.get("/reports/" + created.json().get("reportId"), tokens.token(ADMIN_A));

        assertThat(output.getAll()).contains("POST \"/reports\"").doesNotContain("PrivateMarker", "Jane Synthetic");
    }

    /** US-02: a triage reason may quote a private report, so it stays out of the logs too. */
    @Test
    void triageReasonNeverReachesTheLogs(CapturedOutput output) {
        String reason = MARKER + " reports sparks from the socket.";
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        String key = UUID.randomUUID().toString();

        assertThat(triage(ADMIN_A, issueId, "\"1\"", key, triageBody("electrical", "critical", reason)).status())
                .isEqualTo(200);
        triage(ADMIN_A, issueId, "\"1\"", key, triageBody("electrical", "critical", reason));        // replay
        triage(ADMIN_A, issueId, "\"1\"", key, triageBody("electrical", "low", reason));             // 422
        triage(ADMIN_A, issueId, 1, "electrical", "critical", reason);                                // 409
        triage(ADMIN_A, issueId, 2, "gardening", "critical", reason + "x".repeat(500));               // 400
        triage(RESIDENT_A1, issueId, 2, "electrical", "critical", reason);                            // 403
        triage(RESIDENT_B1, issueId, 2, "electrical", "critical", reason);                            // 404
        api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        api.get("/issues/" + issueId + "/history", tokens.token(ADMIN_A));
        api.get("/issues", tokens.token(ADMIN_A));

        assertThat(output.getAll()).contains("/triage").doesNotContain("PrivateMarker", "Jane Synthetic");
    }

    /** US-03: a suggestion's rationale is written from a private report, so it stays out of the logs too. */
    @Test
    void suggestionRationaleNeverReachesTheLogs(CapturedOutput output) {
        String rationale = MARKER + " mentions a leak.";
        String issueId = submitIssue(RESIDENT_A1, BUILDING_A, AREA_A_GARAGE);
        UUID eventId = UUID.randomUUID();

        assertThat(suggest(issueId, eventId, 1, "plumbing", rationale)).isEqualTo(SuggestionResult.APPLIED);
        suggest(issueId, eventId, 1, "plumbing", rationale);                                          // repeated delivery
        suggest(issueId, UUID.randomUUID(), 1, "plumbing", rationale);                                // late
        assertThatThrownBy(() -> suggest(issueId, UUID.randomUUID(), 1, "gardening", rationale + "x".repeat(240)))
                .isInstanceOf(ValidationException.class);                                             // refused
        api.get("/issues/" + issueId, tokens.token(ADMIN_A));
        api.get("/issues/" + issueId + "/history", tokens.token(ADMIN_A));
        api.get("/issues", tokens.token(ADMIN_A));
        api.get("/issues/" + issueId, tokens.token(RESIDENT_A1));

        assertThat(output.getAll()).contains("triage_suggestions").doesNotContain("PrivateMarker", "Jane Synthetic");
    }

    private SuggestionResult suggest(String issueId, UUID eventId, long sourceVersion, String category, String rationale) {
        return suggestions.record(new RecordTriageSuggestionCommand(
                new IssueId(UUID.fromString(issueId)),
                new SuggestionInput(eventId, sourceVersion, category, "high", rationale, true, "ai", "model-x-1",
                        "triage-v1", null, "triage-output-v1", null),
                "corr-log-privacy-it"));
    }
}
