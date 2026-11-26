package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/**
 * The administrator's scope and terms may quote a private report, so they
 * stay out of the logs, even with DEBUG logging of the web, JDBC and
 * Hibernate layers (which print request and response objects and SQL).
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.pt.isep.bmc=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG",
    "logging.level.org.hibernate.SQL=DEBUG"
})
class LogPrivacyIT extends IntegrationTestSupport {

    static final String MARKER = "PrivateMarker Flat 2B tenant Jane Synthetic";

    @Test
    void scopeAndTermsNeverReachTheLogs(CapturedOutput output) {
        String issueId = triagedPlumbingIssue();
        String token = tokens.token(ADMIN_A);
        Map<String, Object> body = withMarker(draftBody(issueId, PLUMBING_CO));
        String key = UUID.randomUUID().toString();

        var created = api.post("/interventions", token, key, body);
        assertThat(created.status()).isEqualTo(201);
        String interventionId = (String) created.json().get("interventionId");
        api.post("/interventions", token, key, body);                                                // replay
        api.post("/interventions", token, key, withMarker(draftBody(issueId, AQUA_PLUMBERS)));       // 422
        Map<String, Object> tooLong = withMarker(draftBody(issueId, PLUMBING_CO));
        tooLong.put("terms", MARKER + "x".repeat(1_000));
        api.post("/interventions", token, UUID.randomUUID().toString(), tooLong);                    // 400
        api.post("/interventions", token, UUID.randomUUID().toString(), body);                       // 409
        api.post("/interventions", tokens.token(ADMIN_B), UUID.randomUUID().toString(), body);       // 404
        replace(ADMIN_A, interventionId, 1, AQUA_PLUMBERS);
        api.get("/interventions/" + interventionId, token);
        api.get("/interventions?issueId=" + issueId, token);

        assertThat(output.getAll()).contains("/interventions").doesNotContain("PrivateMarker", "Jane Synthetic");
    }

    @Test
    void theContractorsNoteAndCounterproposalNeverReachTheLogs(CapturedOutput output) {
        String interventionId = sentIntervention();
        Map<String, Object> counter = new HashMap<>();
        counter.put("proposalVersion", 1);
        counter.put("decision", "counterpropose");
        counter.put("window", Map.of("start", "2099-11-06T09:00:00Z", "end", "2099-11-06T11:00:00Z"));
        counter.put("terms", MARKER + " can come on Saturday.");
        assertThat(respond(CONTRACTOR, interventionId, 2, counter).status()).isEqualTo(200);
        respond(CONTRACTOR, interventionId, 2, counter);                                             // 409
        assertThat(approve(ADMIN_A, interventionId, 3, 2).status()).isEqualTo(200);
        String other = sentIntervention();
        Map<String, Object> decline = new HashMap<>(Map.of("proposalVersion", 1, "decision", "decline",
                "reasonCode", "other", "note", MARKER + " is on holiday."));
        assertThat(respond(CONTRACTOR, other, 2, decline).status()).isEqualTo(200);
        decline.put("note", MARKER + "x".repeat(600));
        respond(CONTRACTOR, other, 3, decline);                                                       // 400
        api.get("/interventions/" + other, tokens.token(ADMIN_A));
        api.get("/interventions?assigned=me", tokens.token(CONTRACTOR));

        assertThat(output.getAll()).contains("/response").doesNotContain("PrivateMarker", "Jane Synthetic");
    }

    @Test
    void theManualReasonAndTheCancellationNoteNeverReachTheLogs(CapturedOutput output) {
        String interventionId = agreedIntervention();
        calendar.script("create", "conflict");
        runBookingWorker();
        Map<String, Object> manual = new HashMap<>();
        manual.put("window", Map.of("start", "2099-11-06T14:00:00Z", "end", "2099-11-06T16:00:00Z"));
        manual.put("source", "phone");
        manual.put("reason", MARKER + " agreed by phone.");
        String path = "/interventions/" + interventionId;
        assertThat(command(ADMIN_A, path + "/manual-confirmation", 3, UUID.randomUUID().toString(), manual)
                .status()).isEqualTo(200);
        manual.put("reason", MARKER + "x".repeat(600));
        command(ADMIN_A, path + "/manual-confirmation", 4, UUID.randomUUID().toString(), manual);   // 400
        Map<String, Object> cancel = new HashMap<>(Map.of("reasonCode", "other", "note", MARKER + " moved out."));
        assertThat(command(ADMIN_A, path + "/cancellation", 4, UUID.randomUUID().toString(), cancel).status())
                .isEqualTo(200);
        command(ADMIN_A, path + "/cancellation", 4, UUID.randomUUID().toString(), cancel);           // 409
        api.get(path, tokens.token(ADMIN_A));

        assertThat(output.getAll()).contains("/cancellation").doesNotContain("PrivateMarker", "Jane Synthetic");
    }

    private static Map<String, Object> withMarker(Map<String, Object> body) {
        body.put("scopeExtract", MARKER + " reports water above the garage light.");
        body.put("terms", MARKER + " asks to call before arriving.");
        return body;
    }
}
