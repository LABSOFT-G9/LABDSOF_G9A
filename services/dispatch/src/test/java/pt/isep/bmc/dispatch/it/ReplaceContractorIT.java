package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/** US-06: {@code PATCH /interventions/{id}} replaces the contractor of a draft; reload with {@code GET}. */
class ReplaceContractorIT extends IntegrationTestSupport {

    private String issueId;
    private String interventionId;

    private void givenADraft() {
        issueId = triagedPlumbingIssue();
        interventionId = (String) draft(ADMIN_A, issueId, PLUMBING_CO).json().get("interventionId");
    }

    @Test
    void anAdministratorReplacesTheContractorAndTheVersionMoves() {
        givenADraft();

        var response = replace(ADMIN_A, interventionId, 1, AQUA_PLUMBERS);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("ETag")).isEqualTo("\"2\"");
        assertThat(response.json()).isEqualTo(Map.of("interventionId", interventionId, "state", "awaiting_approval",
                "version", 2, "contractorId", AQUA_PLUMBERS));
        var reloaded = api.get("/interventions/" + interventionId, tokens.token(ADMIN_A));
        assertThat(reloaded.json()).containsEntry("version", 2);
        assertThat(Api.map(reloaded.json().get("contractor"))).containsEntry("contractorId", AQUA_PLUMBERS);
        assertThat(Api.map(reloaded.json().get("proposal")))
                .as("the terms stay as they were").containsEntry("proposalVersion", 1).containsEntry("terms", TERMS);
    }

    @Test
    void aStaleVersionIsRefusedWithTheCurrentOneAndChangesNothing() {
        givenADraft();
        assertThat(replace(ADMIN_A, interventionId, 1, AQUA_PLUMBERS).status()).isEqualTo(200);

        var stale = replace(ADMIN_A, interventionId, 1, PLUMBING_CO_TWIN);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json()).containsEntry("code", "VERSION_CONFLICT").containsEntry("currentVersion", 2)
                .containsEntry("currentState", "awaiting_approval");
        assertThat(jdbc.sql("SELECT contractor_id FROM interventions").query(String.class).single())
                .isEqualTo(AQUA_PLUMBERS);
    }

    @Test
    void anIneligibleContractorIsRefusedAndChangesNothing() {
        givenADraft();

        var response = replace(ADMIN_A, interventionId, 1, ELECTRICAL);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "contractorId", "reason", "not_eligible")));
        assertThat(jdbc.sql("SELECT version FROM interventions").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void ifMatchAndAValidContractorAreRequired() {
        givenADraft();
        assertThat(replace(ADMIN_A, interventionId, null, UUID.randomUUID().toString(), AQUA_PLUMBERS).json()
                .get("errors")).isEqualTo(List.of(Map.of("field", "If-Match", "reason", "required")));
        assertThat(replace(ADMIN_A, interventionId, 1, "x").json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "contractorId", "reason", "invalid_format")));
    }

    @Test
    void othersGet404ForTheInterventionAndItsReplacement() {
        givenADraft();
        for (String sub : List.of(RESIDENT_A1, CONTRACTOR, ADMIN_B)) {
            assertThat(replace(sub, interventionId, 1, AQUA_PLUMBERS).status()).as(sub).isEqualTo(404);
            assertThat(api.get("/interventions/" + interventionId, tokens.token(sub)).status()).as(sub).isEqualTo(404);
            assertThat(api.get("/interventions?issueId=" + issueId, tokens.token(sub)).status()).as(sub)
                    .isEqualTo(404);
        }
        assertThat(api.get("/interventions/" + UUID.randomUUID(), tokens.token(ADMIN_A)).status()).isEqualTo(404);
        assertThat(api.get("/interventions/not-a-uuid", tokens.token(ADMIN_A)).status()).isEqualTo(404);
    }

    @Test
    void repeatingTheSameReplacementIsReplayed() {
        givenADraft();
        String key = UUID.randomUUID().toString();

        var first = replace(ADMIN_A, interventionId, "\"1\"", key, AQUA_PLUMBERS);
        var again = replace(ADMIN_A, interventionId, "\"1\"", key, AQUA_PLUMBERS);

        assertThat(first.status()).isEqualTo(200);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json()).isEqualTo(first.json());
        assertThat(jdbc.sql("SELECT version FROM interventions").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void theIssuesInterventionsAreListedForItsAdministrators() {
        givenADraft();

        var list = api.get("/interventions?issueId=" + issueId, tokens.token(ADMIN_A));

        assertThat(list.status()).isEqualTo(200);
        assertThat(Api.list(list.json().get("items"))).hasSize(1);
        assertThat(api.get("/interventions", tokens.token(ADMIN_A)).status()).isEqualTo(400);
    }
}
