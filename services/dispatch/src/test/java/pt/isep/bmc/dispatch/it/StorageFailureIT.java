package pt.isep.bmc.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.dispatch.application.port.in.ApplyIssueStateChangedUseCase;
import pt.isep.bmc.dispatch.application.port.in.ApplyMembershipChangeUseCase;
import pt.isep.bmc.dispatch.support.Api;
import pt.isep.bmc.dispatch.support.IntegrationTestSupport;
import pt.isep.bmc.dispatch.support.SyntheticEvents;
import pt.isep.bmc.dispatch.support.TestDatabase;
import pt.isep.bmc.dispatch.support.TestTokens;

/**
 * If storage fails, no draft or replacement is claimed: reads and writes
 * answer {@code 503} with {@code Retry-After}. Uses its own MySQL, stopped
 * mid-test, as Issue's test does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.connection-timeout=2000")
@DirtiesContext
class StorageFailureIT {

    static final MySQLContainer MYSQL = TestDatabase.newContainer();

    static {
        MYSQL.start();
    }

    @Autowired
    Environment environment;

    @Autowired
    javax.sql.DataSource dataSource;

    @Autowired
    ApplyIssueStateChangedUseCase applyIssue;

    @Autowired
    ApplyMembershipChangeUseCase applyMembership;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry, MYSQL);
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    void databaseDownAnswers503WithoutADraftOrAReplacement() throws Exception {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us06-catalogue.sql"));
        }
        SyntheticEvents events = new SyntheticEvents(applyIssue, applyMembership);
        events.granted(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", 1);
        String issueId = UUID.randomUUID().toString();
        events.issueStateChanged(issueId, BUILDING_A, "triaged", 3, true, "plumbing");
        Api api = new Api(environment.getRequiredProperty("local.server.port", Integer.class));
        String token = TestTokens.get().token(ADMIN_A);
        var created = api.post("/interventions", token, UUID.randomUUID().toString(), body(issueId));
        assertThat(created.status()).isEqualTo(201);
        String interventionId = (String) created.json().get("interventionId");

        MYSQL.stop();

        var draft = api.post("/interventions", token, UUID.randomUUID().toString(), body(UUID.randomUUID().toString()));
        assertThat(draft.status()).isEqualTo(503);
        assertThat(draft.json()).containsEntry("code", "DEPENDENCY_UNAVAILABLE").doesNotContainKey("interventionId");
        assertThat(draft.header("Retry-After")).isEqualTo("5");
        var replacement = api.send("PATCH", "/interventions/" + interventionId, token,
                Map.of("If-Match", "\"1\"", "Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("contractorId", AQUA_PLUMBERS));
        assertThat(replacement.status()).isEqualTo(503);
        assertThat(replacement.json()).containsEntry("code", "DEPENDENCY_UNAVAILABLE").doesNotContainKey("version");
        assertThat(api.get("/interventions/" + interventionId, token).status()).isEqualTo(503);
        assertThat(api.get("/contractors?issueId=" + issueId, token).status()).isEqualTo(503);
    }

    private static Map<String, Object> body(String issueId) {
        Map<String, Object> body = new HashMap<>();
        body.put("issueId", issueId);
        body.put("contractorId", PLUMBING_CO);
        body.put("scopeExtract", "Leak above garage light fitting, level -1. Access via management.");
        body.put("window", Map.of("start", "2099-11-05T09:00:00Z", "end", "2099-11-05T12:00:00Z"));
        body.put("terms", "Inspect and repair; report cost if known.");
        return body;
    }
}
