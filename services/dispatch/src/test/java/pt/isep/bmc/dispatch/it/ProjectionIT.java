package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;

/**
 * Technical design 4.3: Issue's events feed Dispatch's projections by
 * version, so repeated, late and out-of-order events leave the same result.
 */
class ProjectionIT extends IntegrationTestSupport {

    @Test
    void aNewerIssueVersionReplacesTheRowAndOlderOrRepeatedOnesAreIgnored() {
        String issueId = UUID.randomUUID().toString();

        assertThat(events.issueStateChanged(issueId, BUILDING_A, "triaged", 3, true, "plumbing")).isTrue();
        assertThat(events.issueStateChanged(issueId, BUILDING_A, "triaged", 3, true, "plumbing")).isFalse();
        assertThat(events.issueStateChanged(issueId, BUILDING_A, "in_triage", 2, false, null))
                .as("a late, older event").isFalse();
        assertThat(state(issueId)).isEqualTo("triaged");

        assertThat(events.issueStateChanged(issueId, BUILDING_A, "resolved", 7, false, "plumbing")).isTrue();
        assertThat(state(issueId)).isEqualTo("resolved");
        assertThat(count("issue_projection")).isEqualTo(1);
    }

    @Test
    void aReplayedGrantCannotUndoALaterRevocation() {
        String issueId = triagedPlumbingIssue();
        assertThat(events.revoked(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", 2)).isTrue();
        assertThat(events.granted(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", 1)).isFalse();

        assertThat(draft(ADMIN_A, issueId, PLUMBING_CO).status()).isEqualTo(404);
        assertThat(events.granted(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", 3)).isTrue();
        assertThat(draft(ADMIN_A, issueId, PLUMBING_CO).status()).isEqualTo(201);
    }

    @Test
    void aRevocationArrivingBeforeItsGrantStillWins() {
        String membershipId = UUID.randomUUID().toString();
        String user = "0199f2a0-0000-7000-8000-000000000299";
        String issueId = triagedPlumbingIssue();

        events.revoked(membershipId, user, BUILDING_A, "administrator", 2);
        events.granted(membershipId, user, BUILDING_A, "administrator", 1);

        assertThat(api.get("/contractors?issueId=" + issueId, tokens.token(user)).status()).isEqualTo(404);
    }

    @Test
    void racingFirstEventsForTheSameIssueKeepTheNewestVersion() throws Exception {
        String issueId = UUID.randomUUID().toString();
        int versions = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(versions);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int v = 1; v <= versions; v++) {
                long version = v;
                results.add(pool.submit(() -> {
                    start.await();
                    return events.issueStateChanged(issueId, BUILDING_A, "triaged", version, true, "plumbing");
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT version FROM issue_projection WHERE issue_id = :id").param("id", issueId)
                .query(Long.class).single()).isEqualTo(versions);
    }

    private String state(String issueId) {
        return jdbc.sql("SELECT state FROM issue_projection WHERE issue_id = :id").param("id", issueId)
                .query(String.class).single();
    }
}
