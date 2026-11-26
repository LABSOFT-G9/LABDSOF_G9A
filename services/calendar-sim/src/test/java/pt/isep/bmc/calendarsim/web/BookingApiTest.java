package pt.isep.bmc.calendarsim.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.calendarsim.web.SimClient.booking;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The simulator over HTTP, with the control API on: every outcome of
 * technical design 4.5 that Dispatch must handle (US-08).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sim.control-enabled=true", "sim.hang=PT2S", "management.server.port=0"})
class BookingApiTest {

    static final String START = "2099-11-05T09:00:00Z";
    static final String END = "2099-11-05T12:00:00Z";

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    SimClient sim;
    String requestId;

    @BeforeEach
    void reset() throws Exception {
        sim = new SimClient(port);
        sim.control("DELETE", "/bookings");
        requestId = UUID.randomUUID().toString();
    }

    SimClient.Response book(String contractor) throws Exception {
        return sim.create(requestId, booking(requestId, contractor, START, END));
    }

    @Test
    void aBookingIsConfirmedWithAReferenceAndTheSameRequestReplaysIt() throws Exception {
        var created = book("SIM-CTR-1");

        assertThat(created.status()).isEqualTo(201);
        Map<String, Object> body = created.json();
        assertThat(body).containsEntry("requestId", requestId).containsEntry("status", "confirmed")
                .containsEntry("window", Map.of("start", START, "end", END));
        assertThat((String) body.get("externalRef")).startsWith("SIM-BK-");

        var again = book("SIM-CTR-1");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json()).isEqualTo(body);
        assertThat(sim.find(requestId).items()).containsExactly(body);
        assertThat(sim.find(UUID.randomUUID().toString()).items()).isEmpty();
    }

    @Test
    void timesWithAnOffsetAreAnsweredInUtc() throws Exception {
        var created = sim.create(requestId, booking(requestId, "SIM-CTR-1", "2099-11-05T10:00:00+01:00",
                "2099-11-05T13:00:00+01:00"));

        assertThat(created.json().get("window")).isEqualTo(Map.of("start", START, "end", END));
    }

    @Test
    void anInvalidRequestIs422WithEveryFieldAndBooksNothing() throws Exception {
        Map<String, Object> bad = new HashMap<>(booking(requestId, " ", START, "2099-11-05T08:00:00Z"));
        bad.put("summary", "x".repeat(201));

        var response = sim.create("another-key", bad);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()).containsEntry("code", "INVALID_REQUEST");
        assertThat(response.json().get("errors")).isEqualTo(List.of(
                Map.of("field", "Idempotency-Key", "reason", "not_request_id"),
                Map.of("field", "contractorRef", "reason", "required"),
                Map.of("field", "summary", "reason", "too_long"),
                Map.of("field", "window.end", "reason", "not_after_start")));
        assertThat(sim.create(null, booking(requestId, "SIM-CTR-1", START, END)).status()).isEqualTo(422);
        assertThat(sim.find(requestId).items()).isEmpty();
    }

    @Test
    void theSameRequestIdWithOtherContentIs422() throws Exception {
        book("SIM-CTR-1");

        var reused = book("SIM-CTR-2");

        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.json()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void anOverlappingBookingOfTheSameContractorIsASlotConflict() throws Exception {
        book("SIM-CTR-1");
        String other = UUID.randomUUID().toString();

        var conflict = sim.create(other, booking(other, "SIM-CTR-1", "2099-11-05T11:00:00Z", "2099-11-05T13:00:00Z"));

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.json()).containsEntry("code", "SLOT_CONFLICT");
    }

    @Test
    void scriptedConflictAndUnavailableBookNothingAndThenTheCalendarWorksAgain() throws Exception {
        sim.script("create", List.of(Map.of("type", "conflict"), Map.of("type", "unavailable")));

        assertThat(book("SIM-CTR-1").status()).isEqualTo(409);
        var unavailable = book("SIM-CTR-1");
        assertThat(unavailable.status()).isEqualTo(503);
        assertThat(unavailable.json()).containsEntry("code", "UNAVAILABLE");
        assertThat(sim.find(requestId).items()).isEmpty();
        assertThat(book("SIM-CTR-1").status()).isEqualTo(201);
    }

    @Test
    void aScriptedInvalidAnswerLooksLikeAConfirmationButHasNoReferenceAndBooksNothing() throws Exception {
        sim.script("create", List.of(Map.of("type", "invalid")));

        var invalid = book("SIM-CTR-1");

        assertThat(invalid.status()).isEqualTo(201);
        assertThat(invalid.json()).containsEntry("externalRef", null).containsEntry("window", null);
        assertThat(sim.find(requestId).items()).isEmpty();
    }

    @Test
    void aTimeoutAfterCreateLeavesABookingThatReconciliationFinds() throws Exception {
        sim.script("create", List.of(Map.of("type", "timeout_after_create")));

        assertThatThrownBy(() -> sim.create(requestId, booking(requestId, "SIM-CTR-1", START, END),
                Duration.ofMillis(500))).isInstanceOf(HttpTimeoutException.class);

        var found = sim.find(requestId).items();
        assertThat(found).singleElement().satisfies(b -> assertThat(b).containsEntry("status", "confirmed"));
        assertThat(book("SIM-CTR-1").status()).as("re-sending with the same key").isEqualTo(200);
        assertThat(sim.get("http://localhost:" + port + "/control/bookings").items()).hasSize(1);
    }

    @Test
    void aDelayAnswersLateButNormally() throws Exception {
        sim.script("create", List.of(Map.of("type", "delay", "delayMs", 300)));

        long start = System.nanoTime();
        var created = book("SIM-CTR-1");

        assertThat(created.status()).isEqualTo(201);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void concurrentRetriesWithOneKeyCreateOneBooking() throws Exception {
        int clients = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SimClient.Response>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return book("SIM-CTR-1");
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<SimClient.Response> future : futures) {
                statuses.add(future.get().status());
            }
            assertThat(statuses).containsOnly(200, 201).containsOnlyOnce(201);
        }
        assertThat(sim.get("http://localhost:" + port + "/control/bookings").items()).hasSize(1);
    }

    @Test
    void reconciliationCanBeUnavailableOrInvalidToo() throws Exception {
        book("SIM-CTR-1");
        sim.script("query", List.of(Map.of("type", "unavailable"), Map.of("type", "invalid")));

        assertThat(sim.find(requestId).status()).isEqualTo(503);
        assertThat(sim.find(requestId).items()).singleElement()
                .satisfies(b -> assertThat(b).containsEntry("externalRef", null));
        assertThat(sim.find(requestId).items()).singleElement()
                .satisfies(b -> assertThat(b.get("externalRef")).isNotNull());
    }

    @Test
    void cancellationIsIdempotentNeedsAKeyAndCanBeUnavailable() throws Exception {
        String ref = (String) book("SIM-CTR-1").json().get("externalRef");

        assertThat(sim.cancel(ref, null).status()).isEqualTo(422);
        sim.script("cancel", List.of(Map.of("type", "unavailable")));
        assertThat(sim.cancel(ref, "k1").status()).isEqualTo(503);
        var cancelled = sim.cancel(ref, "k1");
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.json()).containsEntry("status", "cancelled").containsEntry("externalRef", ref);
        assertThat(sim.cancel(ref, "k1").status()).isEqualTo(200);
        assertThat(sim.find(requestId).items()).singleElement()
                .satisfies(b -> assertThat(b).containsEntry("status", "cancelled"));
        assertThat(sim.cancel("SIM-BK-0", "k2").status()).isEqualTo(404);
    }

    @Test
    void aLostBookingIsNoLongerFound() throws Exception {
        String ref = (String) book("SIM-CTR-1").json().get("externalRef");

        assertThat(sim.control("DELETE", "/bookings/" + ref).status()).isEqualTo(204);

        assertThat(sim.find(requestId).items()).isEmpty();
        assertThat(sim.control("DELETE", "/bookings/" + ref).status()).isEqualTo(404);
    }

    @Test
    void theControlApiRefusesOutcomesThatDoNotFitTheOperation() throws Exception {
        var refused = sim.script("query", List.of(Map.of("type", "conflict"), Map.of("type", "delay"),
                Map.of("type", "explode")));

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.json().get("errors")).isEqualTo(List.of(
                Map.of("field", "outcomes[0].type", "reason", "not_for_operation"),
                Map.of("field", "outcomes[1].delayMs", "reason", "invalid"),
                Map.of("field", "outcomes[2].type", "reason", "invalid")));
        assertThat(sim.script("restart", List.of(Map.of("type", "ok"))).status()).isEqualTo(422);
        sim.script("cancel", List.of(Map.of("type", "unavailable")));
        assertThat(sim.control("GET", "/outcomes").json().get("cancel"))
                .isEqualTo(List.of(Map.of("type", "unavailable", "delayMs", 0)));
        assertThat(sim.control("DELETE", "/outcomes").status()).isEqualTo(204);
        assertThat(sim.control("GET", "/outcomes").json().get("cancel")).isEqualTo(List.of());
    }

    @Test
    void healthIsOnTheManagementPort() throws Exception {
        assertThat(sim.get("http://localhost:" + managementPort + "/healthz").status()).isEqualTo(200);
        assertThat(sim.get("http://localhost:" + managementPort + "/readyz").status()).isEqualTo(200);
    }
}
