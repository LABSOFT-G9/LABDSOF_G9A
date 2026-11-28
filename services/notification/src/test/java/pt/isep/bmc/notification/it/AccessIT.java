package pt.isep.bmc.notification.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.notification.support.Fixtures.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.notification.support.Api;
import pt.isep.bmc.notification.support.IntegrationTestSupport;

/** Only the recipient reads a notification; paging cannot reach another inbox. */
class AccessIT extends IntegrationTestSupport {

    @Test
    void eachUserSeesOnlyTheirOwnNotifications() {
        requests.deliver(triaged(RESIDENT_A1));
        requests.deliver(triaged(RESIDENT_A2));

        List<Object> a1 = items(api.get("/notifications", tokens.token(RESIDENT_A1)));
        List<Object> a2 = items(api.get("/notifications", tokens.token(RESIDENT_A2)));
        assertThat(a1).hasSize(1);
        assertThat(a2).hasSize(1);
        assertThat(Api.map(a1.getFirst()).get("id")).isNotEqualTo(Api.map(a2.getFirst()).get("id"));
        assertThat(items(api.get("/notifications", tokens.token(ADMIN_A)))).isEmpty();
    }

    @Test
    void anotherInboxsIdAsCursorOnlyBoundsTheCallersOwnRows() {
        requests.deliver(triaged(RESIDENT_A1));
        requests.deliver(triaged(RESIDENT_A2));
        String theirs = (String) Api.map(items(api.get("/notifications", tokens.token(RESIDENT_A2))).getFirst()).get("id");

        var page = api.get("/notifications?before=" + theirs, tokens.token(RESIDENT_A1));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).doesNotContain(theirs);
    }

    @Test
    void pagesFollowEachOtherNewestFirst() {
        for (int i = 0; i < 5; i++) {
            requests.deliver(triaged(RESIDENT_A1));
        }
        String token = tokens.token(RESIDENT_A1);
        Map<String, Object> first = api.get("/notifications?limit=3", token).json();
        assertThat(items(first)).hasSize(3);
        Map<String, Object> second = api.get("/notifications?limit=3&before=" + first.get("nextBefore"), token).json();
        assertThat(items(second)).hasSize(2);
        assertThat(second.get("nextBefore")).isNull();
    }

    @Test
    void invalidPagingParametersAnswer400WithEveryField() {
        var response = api.get("/notifications?limit=500&before=not-an-id", tokens.token(RESIDENT_A1));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(Api.list(response.json().get("errors"))).containsExactlyInAnyOrder(
                Map.of("field", "limit", "reason", "out_of_range"),
                Map.of("field", "before", "reason", "invalid_format"));
    }

    private static List<Object> items(Api.Response response) {
        assertThat(response.status()).isEqualTo(200);
        return items(response.json());
    }

    private static List<Object> items(Map<String, Object> body) {
        return Api.list(body.get("items"));
    }
}
