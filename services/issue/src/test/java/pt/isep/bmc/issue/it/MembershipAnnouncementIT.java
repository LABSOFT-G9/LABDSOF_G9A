package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pt.isep.bmc.issue.application.port.in.AnnounceMembershipsUseCase;
import pt.isep.bmc.issue.support.IntegrationTestSupport;
import tools.jackson.databind.json.JsonMapper;

/**
 * Memberships stored without an event (the synthetic seed) reach the outbox
 * as {@code MembershipGranted} / {@code MembershipRevoked}, once per version
 * (technical design 4.3 and 4.4), on real MySQL.
 */
class MembershipAnnouncementIT extends IntegrationTestSupport {

    static final String ADMIN_A_MEMBERSHIP = "0199f2a0-0000-7000-8000-00000000d004";
    static final String REVOKED_MEMBERSHIP = "0199f2a0-0000-7000-8000-00000000d006";

    @Autowired
    AnnounceMembershipsUseCase announce;

    @Test
    void everyMembershipIsAnnouncedOnceAsACloudEvent() {
        assertThat(announce.announce()).isEqualTo(6);
        assertThat(announce.announce()).as("a second run writes nothing").isZero();
        assertThat(count("outbox")).isEqualTo(6);

        Map<String, Object> granted = envelope(ADMIN_A_MEMBERSHIP);
        assertThat(granted).containsEntry("specversion", "1.0").containsEntry("type", "MembershipGranted")
                .containsEntry("source", "issue").containsEntry("subject", ADMIN_A_MEMBERSHIP)
                .containsEntry("aggregateversion", 1).containsEntry("buildingid", BUILDING_A)
                .containsEntry("data", Map.of("membershipId", ADMIN_A_MEMBERSHIP, "userId", ADMIN_A,
                        "buildingId", BUILDING_A, "role", "administrator", "version", 1));
        assertThat(row(ADMIN_A_MEMBERSHIP)).containsEntry("routing_key", "issue.MembershipGranted")
                .containsEntry("status", "pending").containsEntry("aggregate_version", 1L);

        Map<String, Object> revoked = envelope(REVOKED_MEMBERSHIP);
        assertThat(revoked).containsEntry("type", "MembershipRevoked").containsEntry("aggregateversion", 2);
        assertThat(row(REVOKED_MEMBERSHIP)).containsEntry("routing_key", "issue.MembershipRevoked");
    }

    @Test
    void aNewVersionIsAnnouncedAgain() {
        announce.announce();
        jdbc.sql("UPDATE memberships SET status = 'revoked', version = 2 WHERE id = :id")
                .param("id", ADMIN_A_MEMBERSHIP).update();
        try {
            assertThat(announce.announce()).isEqualTo(1);
            assertThat(jdbc.sql("""
                            SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY aggregate_version
                            """).param("id", ADMIN_A_MEMBERSHIP).query(String.class).list())
                    .containsExactly("MembershipGranted", "MembershipRevoked");
        } finally {
            jdbc.sql("UPDATE memberships SET status = 'active', version = 1 WHERE id = :id")
                    .param("id", ADMIN_A_MEMBERSHIP).update();
        }
    }

    private Map<String, Object> row(String membershipId) {
        return jdbc.sql("SELECT routing_key, status, aggregate_version FROM outbox WHERE aggregate_id = :id")
                .param("id", membershipId).query().singleRow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> envelope(String membershipId) {
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE aggregate_id = :id")
                .param("id", membershipId).query(String.class).single();
        return JsonMapper.builder().build().readValue(payload, Map.class);
    }
}
