package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pt.isep.bmc.dispatch.application.command.PurgeExpiredIdempotencyKeysService;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.support.Api.Response;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/** Safe retries (technical design 3.3), as in Issue: same key → same result, once. */
class IdempotencyIT extends IntegrationTestSupport {

    @Autowired
    TransactionRunner transactions;

    @Autowired
    IdempotencyStore idempotencyStore;

    private Response draftWithKey(String sub, String key, String issueId, String contractorId) {
        return api.post("/interventions", tokens.token(sub), key, draftBody(issueId, contractorId));
    }

    @Test
    void theSameKeyFromAnotherUserIsIndependent() {
        String key = UUID.randomUUID().toString();
        String issueB = UUID.randomUUID().toString();
        events.issueStateChanged(issueB, BUILDING_B, "triaged", 3, true, "plumbing");

        assertThat(draftWithKey(ADMIN_A, key, triagedPlumbingIssue(), PLUMBING_CO).status()).isEqualTo(201);
        assertThat(draftWithKey(ADMIN_B, key, issueB, PLUMBING_B_ONLY).status()).isEqualTo(201);

        assertThat(count("interventions")).isEqualTo(2);
        assertThat(count("idempotency_keys")).isEqualTo(2);
    }

    /** Repeated: a lock or deadlock problem shows up as a 503 in some runs only. */
    @RepeatedTest(5)
    void concurrentRetriesCreateExactlyOneDraft() throws Exception {
        int clients = 12;
        String key = UUID.randomUUID().toString();
        String issueId = triagedPlumbingIssue();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return draftWithKey(ADMIN_A, key, issueId, PLUMBING_CO);
                }));
            }
            start.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
            assertThat(responses).extracting(r -> r.json().get("interventionId"))
                    .containsOnly(responses.getFirst().json().get("interventionId"));
        }
        assertThat(count("interventions")).isEqualTo(1);
        assertThat(count("proposal_versions")).isEqualTo(1);
        assertThat(count("idempotency_keys")).isEqualTo(1);
    }

    @Test
    void aKeyOlderThan24HoursIsFreeAgain() {
        String key = "old-key";
        draftWithKey(ADMIN_A, key, triagedPlumbingIssue(), PLUMBING_CO);
        jdbc.sql("UPDATE idempotency_keys SET created_at = created_at - INTERVAL 25 HOUR,"
                + " expires_at = expires_at - INTERVAL 25 HOUR").update();

        Response reused = draftWithKey(ADMIN_A, key, triagedPlumbingIssue(), AQUA_PLUMBERS);

        assertThat(reused.status()).isEqualTo(201);
        assertThat(count("interventions")).isEqualTo(2);
        assertThat(count("idempotency_keys")).isEqualTo(1);
    }

    @Test
    void purgeDeletesExpiredKeysInBatchesOnMysqlAndKeepsLiveOnes() {
        for (String key : List.of("old-1", "old-2", "old-3")) {
            draftWithKey(ADMIN_A, key, triagedPlumbingIssue(), PLUMBING_CO);
        }
        jdbc.sql("UPDATE idempotency_keys SET expires_at = expires_at - INTERVAL 25 HOUR").update();
        draftWithKey(ADMIN_A, "live", triagedPlumbingIssue(), PLUMBING_CO);

        // Batch size 2: the real SQL runs twice (2 + 1 rows).
        int purged = new PurgeExpiredIdempotencyKeysService(transactions, idempotencyStore, Clock.systemUTC(), 2)
                .purge();

        assertThat(purged).isEqualTo(3);
        assertThat(jdbc.sql("SELECT idem_key FROM idempotency_keys").query(String.class).list())
                .containsExactly("live");
        assertThat(count("interventions")).isEqualTo(4);
    }

    @Test
    void theStoredKeyHoldsOnlyAHashOfTheRequest() {
        draftWithKey(ADMIN_A, "key-1", triagedPlumbingIssue(), PLUMBING_CO);

        String row = jdbc.sql("SELECT CONCAT_WS('|', request_hash, resource_id, expires_at) FROM idempotency_keys")
                .query(String.class).single();

        assertThat(row).doesNotContain("garage", "Inspect").matches("[0-9a-f]{64}\\|.*");
    }
}
