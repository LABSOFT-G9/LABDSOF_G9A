package pt.isep.bmc.notification.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.notification.support.Fixtures.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler.Outcome;
import pt.isep.bmc.notification.support.Api;
import pt.isep.bmc.notification.support.IntegrationTestSupport;
import pt.isep.bmc.notification.support.SyntheticRequests;

/**
 * US-10-01: a request becomes one visible notification per recipient; replays
 * and concurrent deliveries add none; distinct requests stay separate.
 */
class DeliveryIT extends IntegrationTestSupport {

    @Test
    void eachUs10UpdateReachesItsRecipientWithATitleAndALink() {
        String issueId = UUID.randomUUID().toString();
        String interventionId = UUID.randomUUID().toString();
        List<SyntheticRequests.Request> updates = List.of(
                requests.request("issue.triaged", Map.of("issueId", issueId, "category", "plumbing"), RESIDENT_A1),
                requests.request("intervention.contractor_responded",
                        Map.of("issueId", issueId, "interventionId", interventionId, "response", "accepted"), ADMIN_A),
                requests.request("booking.confirmed", Map.of("issueId", issueId, "interventionId", interventionId,
                        "windowStart", "2099-11-05T09:00:00Z", "windowEnd", "2099-11-05T12:00:00Z"), RESIDENT_A1),
                requests.request("intervention.completed",
                        Map.of("issueId", issueId, "interventionId", interventionId), ADMIN_A),
                requests.request("issue.resolved", Map.of("issueId", issueId, "resolutionKind", "validated"),
                        RESIDENT_A1),
                requests.request("contractor.request_sent", Map.of("interventionId", interventionId), CONTRACTOR));
        for (SyntheticRequests.Request update : updates) {
            assertThat(requests.deliver(update)).isEqualTo(Outcome.DELIVERED);
        }

        List<Object> resident = Api.list(api.get("/notifications", tokens.token(RESIDENT_A1)).json().get("items"));
        assertThat(resident).extracting(i -> Api.map(i).get("templateKey"))
                .containsExactly("issue.resolved", "booking.confirmed", "issue.triaged");
        Map<String, Object> newest = Api.map(resident.getFirst());
        assertThat(newest).containsEntry("title", "An issue you follow was resolved.")
                .containsEntry("link", "/issues/" + issueId)
                .containsEntry("priority", "normal")
                .containsKeys("id", "createdAt", "params");

        assertThat(Api.list(api.get("/notifications", tokens.token(ADMIN_A)).json().get("items")))
                .extracting(i -> Api.map(i).get("templateKey"))
                .containsExactly("intervention.completed", "intervention.contractor_responded");
        Map<String, Object> contractor = Api.map(Api.list(
                api.get("/notifications", tokens.token(CONTRACTOR)).json().get("items")).getFirst());
        assertThat(contractor).containsEntry("templateKey", "contractor.request_sent").containsEntry("link", null);
    }

    @Test
    void aRedeliveredRequestCreatesNoSecondNotification() {
        SyntheticRequests.Request request = triaged(RESIDENT_A1, RESIDENT_A2);
        assertThat(requests.deliver(request)).isEqualTo(Outcome.DELIVERED);
        assertThat(requests.deliver(request)).isEqualTo(Outcome.DUPLICATE);
        assertThat(requests.deliver(request)).isEqualTo(Outcome.DUPLICATE);
        assertThat(count("notifications")).isEqualTo(2);
        assertThat(count("deliveries")).isEqualTo(2);
    }

    @Test
    void distinctRequestsAboutTheSameIssueStaySeparate() {
        SyntheticRequests.Request first = triaged(RESIDENT_A1);
        SyntheticRequests.Request second = requests.request("issue.resolved",
                Map.of("issueId", first.params().get("issueId")), RESIDENT_A1);
        requests.deliver(first);
        requests.deliver(second);
        assertThat(Api.list(api.get("/notifications", tokens.token(RESIDENT_A1)).json().get("items"))).hasSize(2);
    }

    @Test
    void concurrentDeliveriesOfOneRequestCreateOneNotificationPerRecipient() throws Exception {
        SyntheticRequests.Request request = triaged(RESIDENT_A1, RESIDENT_A2, ADMIN_A);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> outcomes = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Outcome> delivery = () -> {
                    start.await();
                    return requests.deliver(request);
                };
                outcomes.add(pool.submit(delivery));
            }
            start.countDown();
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> outcome : outcomes) {
                results.add(outcome.get());
            }
            assertThat(results).doesNotContain(Outcome.EXHAUSTED, Outcome.REJECTED);
            assertThat(results).filteredOn(o -> o == Outcome.DELIVERED).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("notifications")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT COUNT(DISTINCT recipient_user_id) FROM notifications").query(Long.class).single())
                .isEqualTo(3);
    }

    @Test
    void anInvalidRequestCreatesNothing() {
        SyntheticRequests.Request request = requests.request("issue.triaged",
                Map.of("issueId", UUID.randomUUID().toString(), "reportText", "The tap in 2B leaks"), RESIDENT_A1);
        assertThat(requests.deliver(request)).isEqualTo(Outcome.REJECTED);
        assertThat(count("notifications")).isZero();
        assertThat(count("deliveries")).isZero();
    }
}
