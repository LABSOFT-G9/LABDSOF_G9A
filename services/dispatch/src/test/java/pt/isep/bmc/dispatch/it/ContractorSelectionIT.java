package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/** US-06: {@code GET /contractors?issueId=}. */
class ContractorSelectionIT extends IntegrationTestSupport {

    @Test
    void anAdministratorSeesTheEligibleContractorsInTheDocumentedOrderWithTheCriteria() {
        String issueId = triagedPlumbingIssue();

        var response = api.get("/contractors?issueId=" + issueId, tokens.token(ADMIN_A));

        assertThat(response.status()).isEqualTo(200);
        Map<String, Object> body = response.json();
        assertThat(body).containsEntry("issueId", issueId);
        assertThat(body.get("criteria")).isEqualTo(Map.of(
                "category", "plumbing",
                "issueState", "triaged",
                "dispatchable", true,
                "eligibility", List.of("active", "specialisation", "coverage:building"),
                "order", List.of("displayName", "contractorId")));
        List<Map<String, Object>> items = items(body);
        assertThat(items).extracting(item -> item.get("contractorId"))
                .as("by name, then the tie by ID; B-only, inactive and electrical left out")
                .containsExactly(AQUA_PLUMBERS, PLUMBING_CO, PLUMBING_CO_TWIN);
        assertThat(items.getFirst()).containsEntry("displayName", "Aqua Plumbers")
                .containsEntry("synthetic", true)
                .containsEntry("specialisations", List.of("plumbing"))
                .containsEntry("matched", List.of("specialisation:plumbing", "coverage:building"))
                .containsEntry("unknown", List.of("price", "distance", "availability"))
                .doesNotContainKeys("price", "availability", "userId", "coverage");
    }

    @Test
    void anIssueWithoutADecidedCategoryHasNobodyEligibleAndSaysWhy() {
        String issueId = UUID.randomUUID().toString();
        events.issueStateChanged(issueId, BUILDING_A, "received", 1, false, null);

        var response = api.get("/contractors?issueId=" + issueId, tokens.token(ADMIN_A));

        assertThat(response.status()).isEqualTo(200);
        assertThat(items(response.json())).isEmpty();
        assertThat(Api.map(response.json().get("criteria"))).containsEntry("category", null)
                .containsEntry("dispatchable", false).containsEntry("issueState", "received");
    }

    @Test
    void noCandidateInTheBuildingGivesAnEmptyList() {
        String issueId = UUID.randomUUID().toString();
        events.issueStateChanged(issueId, BUILDING_B, "triaged", 2, true, "lift");

        var response = api.get("/contractors?issueId=" + issueId, tokens.token(ADMIN_B));

        assertThat(response.status()).isEqualTo(200);
        assertThat(items(response.json())).isEmpty();
    }

    @Test
    void residentsContractorsOtherAdministratorsAndUnknownIssuesGet404() {
        String issueId = triagedPlumbingIssue();
        for (String sub : List.of(RESIDENT_A1, CONTRACTOR, ADMIN_B)) {
            var response = api.get("/contractors?issueId=" + issueId, tokens.token(sub));
            assertThat(response.status()).as(sub).isEqualTo(404);
            assertThat(response.json()).containsEntry("code", "NOT_FOUND");
        }
        assertThat(api.get("/contractors?issueId=" + UUID.randomUUID(), tokens.token(ADMIN_A)).status())
                .isEqualTo(404);
    }

    @Test
    void aRevokedAdministratorLosesAccessAsSoonAsTheRevocationIsApplied() {
        String issueId = triagedPlumbingIssue();
        events.revoked(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", 2);

        assertThat(api.get("/contractors?issueId=" + issueId, tokens.token(ADMIN_A)).status()).isEqualTo(404);
    }

    @Test
    void theIssueIdIsRequiredAndMustBeAUuid() {
        var missing = api.get("/contractors", tokens.token(ADMIN_A));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.json().get("errors")).isEqualTo(List.of(Map.of("field", "issueId", "reason", "required")));
        assertThat(api.get("/contractors?issueId=garage", tokens.token(ADMIN_A)).json().get("errors"))
                .isEqualTo(List.of(Map.of("field", "issueId", "reason", "invalid_format")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }
}
