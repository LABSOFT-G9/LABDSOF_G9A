package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

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
import pt.isep.bmc.issue.application.command.PurgeExpiredIdempotencyKeysService;
import pt.isep.bmc.issue.application.port.out.IdempotencyStore;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.support.Api.Response;
import pt.isep.bmc.issue.support.IntegrationTestSupport;

/** Safe retries (US-01, technical design 3.3): same key → same receipt, once. */
class IdempotencyIT extends IntegrationTestSupport {

    @Autowired
    TransactionRunner transactions;

    @Autowired
    IdempotencyStore idempotencyStore;

    @Test
    void retryWithTheSameKeyReturnsTheOriginalReceipt() {
        String key = UUID.randomUUID().toString();
        String token = tokens.token(RESIDENT_A1);
        Response first = api.post("/reports", token, key, report(BUILDING_A, AREA_A_GARAGE, TEXT));
        Response retry = api.post("/reports", token, key, report(BUILDING_A, AREA_A_GARAGE, TEXT));

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.json()).isEqualTo(first.json());
        assertThat(retry.header("ETag")).isEqualTo(first.header("ETag"));
        assertThat(count("reports")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(2);
    }

    @Test
    void reusedKeyWithDifferentContentIsRejectedAndOriginalKept() {
        String key = UUID.randomUUID().toString();
        String token = tokens.token(RESIDENT_A1);
        Response first = api.post("/reports", token, key, report(BUILDING_A, AREA_A_GARAGE, TEXT));
        Response reuse = api.post("/reports", token, key, report(BUILDING_A, AREA_A_HALL, TEXT));

        assertThat(reuse.status()).isEqualTo(422);
        assertThat(reuse.json()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(count("reports")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT area_id FROM reports WHERE id = ?").param(first.json().get("reportId"))
                .query(String.class).single()).isEqualTo(AREA_A_GARAGE);
    }

    @Test
    void sameKeyFromAnotherUserIsIndependent() {
        String key = UUID.randomUUID().toString();
        api.post("/reports", tokens.token(RESIDENT_A1), key, report(BUILDING_A, AREA_A_GARAGE, TEXT));
        Response other = api.post("/reports", tokens.token(RESIDENT_A2), key, report(BUILDING_A, AREA_A_GARAGE, TEXT));
        assertThat(other.status()).isEqualTo(201);
        assertThat(count("reports")).isEqualTo(2);
    }

    /** Repeated: a lock or deadlock problem shows up as a 503 in some runs only. */
    @RepeatedTest(5)
    void concurrentRetriesCreateExactlyOneReport() throws Exception {
        int clients = 12;
        String key = UUID.randomUUID().toString();
        String token = tokens.token(RESIDENT_A1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return api.post("/reports", token, key, report(BUILDING_A, AREA_A_GARAGE, TEXT));
                }));
            }
            start.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
            assertThat(responses).extracting(r -> r.json().get("reportId")).containsOnly(
                    responses.getFirst().json().get("reportId"));
        }
        assertThat(count("reports")).isEqualTo(1);
        assertThat(count("issues")).isEqualTo(1);
        assertThat(count("issue_history")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(2);
        assertThat(count("idempotency_keys")).isEqualTo(1);
    }

    @Test
    void keyOlderThan24HoursIsFreeAgain() {
        String key = "old-key";
        api.post("/reports", tokens.token(RESIDENT_A1), key, report(BUILDING_A, AREA_A_GARAGE, TEXT));
        jdbc.sql("UPDATE idempotency_keys SET created_at = created_at - INTERVAL 25 HOUR,"
                + " expires_at = expires_at - INTERVAL 25 HOUR").update();

        Response reused = api.post("/reports", tokens.token(RESIDENT_A1), key, report(BUILDING_A, AREA_A_HALL, TEXT));
        assertThat(reused.status()).isEqualTo(201);
        assertThat(count("reports")).isEqualTo(2);
        assertThat(count("idempotency_keys")).isEqualTo(1);
    }

    @Test
    void purgeDeletesExpiredKeysInBatchesOnMysqlAndKeepsLiveOnes() {
        for (String key : List.of("old-1", "old-2", "old-3")) {
            api.post("/reports", tokens.token(RESIDENT_A1), key, report(BUILDING_A, AREA_A_GARAGE, TEXT));
        }
        jdbc.sql("UPDATE idempotency_keys SET expires_at = expires_at - INTERVAL 25 HOUR").update();
        api.post("/reports", tokens.token(RESIDENT_A1), "live", report(BUILDING_A, AREA_A_GARAGE, TEXT));

        // Batch size 2: the real SQL runs twice (2 + 1 rows).
        int purged = new PurgeExpiredIdempotencyKeysService(transactions, idempotencyStore, Clock.systemUTC(), 2).purge();

        assertThat(purged).isEqualTo(3);
        assertThat(jdbc.sql("SELECT idem_key FROM idempotency_keys").query(String.class).list()).containsExactly("live");
        assertThat(count("reports")).isEqualTo(4);
    }

    @Test
    void storedKeyHoldsOnlyAHashOfTheRequest() {
        api.post("/reports", tokens.token(RESIDENT_A1), "key-1", report(BUILDING_A, AREA_A_GARAGE, TEXT));
        String row = jdbc.sql("SELECT CONCAT_WS('|', request_hash, resource_id, expires_at) FROM idempotency_keys")
                .query(String.class).single();
        assertThat(row).doesNotContain("dripping").matches("[0-9a-f]{64}\\|.*");
    }
}
