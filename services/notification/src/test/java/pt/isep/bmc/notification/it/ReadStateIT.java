package pt.isep.bmc.notification.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.notification.support.Fixtures.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.notification.support.Api;
import pt.isep.bmc.notification.support.IntegrationTestSupport;

/** Read state and the unread count: only for the caller's own notifications. */
class ReadStateIT extends IntegrationTestSupport {

    @Test
    void aNewNotificationIsUnreadAndCountsUntilTheRecipientReadsIt() {
        requests.deliver(triaged(RESIDENT_A1));
        requests.deliver(triaged(RESIDENT_A1));
        String token = tokens.token(RESIDENT_A1);
        assertThat(unread(token)).isEqualTo(2);

        Map<String, Object> newest = Api.map(items(token).getFirst());
        assertThat(newest).containsEntry("read", false).containsEntry("readAt", null);

        String id = (String) newest.get("id");
        assertThat(api.post("/notifications/" + id + "/read", token, null, "").status()).isEqualTo(204);
        String readAt = (String) Api.map(items(token).getFirst()).get("readAt");
        assertThat(readAt).isNotNull();
        assertThat(Api.map(items(token).getFirst())).containsEntry("read", true);
        assertThat(unread(token)).isEqualTo(1);

        // Reading again answers the same and keeps the first time.
        assertThat(api.post("/notifications/" + id + "/read", token, null, "").status()).isEqualTo(204);
        assertThat(Api.map(items(token).getFirst())).containsEntry("readAt", readAt);
    }

    @Test
    void anotherUsersNotificationAnswers404AndStaysUnread() {
        requests.deliver(triaged(RESIDENT_A2));
        String theirs = (String) Api.map(items(tokens.token(RESIDENT_A2)).getFirst()).get("id");

        var response = api.post("/notifications/" + theirs + "/read", tokens.token(RESIDENT_A1), null, "");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json()).containsEntry("code", "NOT_FOUND");
        assertThat(api.post("/notifications/" + UUID.randomUUID() + "/read", tokens.token(RESIDENT_A1), null, "")
                .status()).isEqualTo(404);
        assertThat(unread(tokens.token(RESIDENT_A2))).isEqualTo(1);
    }

    @Test
    void aMalformedIdAnswers400() {
        var response = api.post("/notifications/not-an-id/read", tokens.token(RESIDENT_A1), null, "");
        assertThat(response.status()).isEqualTo(400);
        assertThat(Api.list(response.json().get("errors"))).containsExactly(Map.of("field", "id", "reason", "invalid_format"));
    }

    @Test
    void markingAllReadOnlyTouchesTheCallersInbox() {
        requests.deliver(triaged(RESIDENT_A1));
        requests.deliver(triaged(RESIDENT_A1));
        requests.deliver(triaged(RESIDENT_A2));

        var all = api.post("/notifications/read-all", tokens.token(RESIDENT_A1), null, "");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.json()).containsEntry("marked", 2);
        assertThat(unread(tokens.token(RESIDENT_A1))).isZero();
        assertThat(unread(tokens.token(RESIDENT_A2))).isEqualTo(1);
    }

    @Test
    void markingReadNeedsTheCsrfHeader() {
        requests.deliver(triaged(RESIDENT_A1));
        String token = tokens.token(RESIDENT_A1);
        String id = (String) Api.map(items(token).getFirst()).get("id");
        assertThat(api.withoutCsrfHeader().post("/notifications/" + id + "/read", token, null, "").status())
                .isEqualTo(403);
        assertThat(api.withoutCsrfHeader().post("/notifications/read-all", token, null, "").status()).isEqualTo(403);
        assertThat(unread(token)).isEqualTo(1);
    }

    private long unread(String token) {
        var response = api.get("/notifications/unread-count", token);
        assertThat(response.status()).isEqualTo(200);
        return ((Number) response.json().get("unread")).longValue();
    }

    private List<Object> items(String token) {
        return Api.list(api.get("/notifications", token).json().get("items"));
    }
}
