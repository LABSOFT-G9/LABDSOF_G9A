package pt.isep.bmc.dispatch.support;

import static pt.isep.bmc.dispatch.support.Fixtures.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.dispatch.application.port.in.ApplyIssueStateChangedUseCase;
import pt.isep.bmc.dispatch.application.port.in.ApplyMembershipChangeUseCase;

/**
 * Boots the whole service on a random port against a real MySQL 8.4 (Flyway
 * migrations included) and the test JWKS. Every test starts from the test
 * catalogue and from synthetic Issue events: Administrator A administers
 * Building A, Administrator B Building B, Resident A1 lives in Building A.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    /** Tables emptied before each test, children first. */
    private static final String[] TABLES = {
        "outbox", "contractor_responses", "proposal_versions", "interventions", "contractor_coverage", "contractor_specialisations", "contractors",
        "issue_projection", "membership_projection", "idempotency_keys"};

    protected static final String SCOPE = "Leak above garage light fitting, level -1. Access via management.";
    protected static final String TERMS = "Inspect and repair; report cost if known.";

    @Autowired
    protected Environment environment;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    private ApplyIssueStateChangedUseCase applyIssue;

    @Autowired
    private ApplyMembershipChangeUseCase applyMembership;

    protected final TestTokens tokens = TestTokens.get();
    protected Api api;
    protected SyntheticEvents events;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, TestDatabase.shared());
    }

    public static void register(DynamicPropertyRegistry registry, MySQLContainer mysql) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("bmc.security.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("bmc.security.jwk-set-uri", () -> TestTokens.get().jwksUri());
        registry.add("bmc.scheduling.enabled", () -> "false");
        registry.add("management.server.port", () -> "0");
    }

    @BeforeEach
    void resetData() throws Exception {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us06-catalogue.sql"));
        }
        api = new Api(environment.getRequiredProperty("local.server.port", Integer.class));
        events = new SyntheticEvents(applyIssue, applyMembership);
        events.granted(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", 1);
        events.granted(MEMBERSHIP_ADMIN_B, ADMIN_B, BUILDING_B, "administrator", 1);
        events.granted(MEMBERSHIP_RESIDENT_A1, RESIDENT_A1, BUILDING_A, "resident", 1);
    }

    /** A new issue in the projection: triaged plumbing in Building A, version 3, dispatchable. */
    protected String triagedPlumbingIssue() {
        String issueId = UUID.randomUUID().toString();
        events.issueStateChanged(issueId, BUILDING_A, "triaged", 3, true, "plumbing");
        return issueId;
    }

    protected Map<String, Object> draftBody(String issueId, String contractorId) {
        Map<String, Object> body = new HashMap<>();
        body.put("issueId", issueId);
        body.put("contractorId", contractorId);
        body.put("scopeExtract", SCOPE);
        body.put("window", Map.of("start", "2099-11-05T09:00:00Z", "end", "2099-11-05T12:00:00Z"));
        body.put("terms", TERMS);
        return body;
    }

    /** {@code POST /interventions} with a fresh idempotency key. */
    protected Api.Response draft(String sub, String issueId, String contractorId) {
        return api.post("/interventions", tokens.token(sub), UUID.randomUUID().toString(),
                draftBody(issueId, contractorId));
    }

    /** {@code PATCH /interventions/{id}} with {@code If-Match: "<version>"} and a fresh idempotency key. */
    protected Api.Response replace(String sub, String interventionId, long version, String contractorId) {
        return replace(sub, interventionId, "\"" + version + "\"", UUID.randomUUID().toString(), contractorId);
    }

    protected Api.Response replace(String sub, String interventionId, String ifMatch, String key, String contractorId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("If-Match", ifMatch);
        headers.put("Idempotency-Key", key);
        Map<String, Object> body = new HashMap<>();
        body.put("contractorId", contractorId);
        return api.send("PATCH", "/interventions/" + interventionId, tokens.token(sub), headers, body);
    }

    /** {@code POST /interventions/{id}/approval} with {@code If-Match: "<version>"} and a fresh key. */
    protected Api.Response approve(String sub, String interventionId, long version, Integer proposalVersion) {
        return approve(sub, interventionId, version, UUID.randomUUID().toString(), proposalVersion);
    }

    protected Api.Response approve(String sub, String interventionId, long version, String key, Integer proposalVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("proposalVersion", proposalVersion);
        return command(sub, "/interventions/" + interventionId + "/approval", version, key, body);
    }

    /** {@code POST /interventions/{id}/response} as the given caller, with a fresh key. */
    protected Api.Response respond(String sub, String interventionId, long version, Map<String, Object> body) {
        return command(sub, "/interventions/" + interventionId + "/response", version, UUID.randomUUID().toString(),
                body);
    }

    /** {@code POST /interventions/{id}/proposals} with the draft's window and new scope and terms. */
    protected Api.Response changeTerms(String sub, String interventionId, long version) {
        Map<String, Object> body = new HashMap<>(draftBody(null, null));
        body.remove("issueId");
        body.remove("contractorId");
        body.put("terms", "Inspect, repair and report the cost before any extra work.");
        return command(sub, "/interventions/" + interventionId + "/proposals", version, UUID.randomUUID().toString(),
                body);
    }

    protected Api.Response command(String sub, String path, long version, String key, Map<String, Object> body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("If-Match", "\"" + version + "\"");
        headers.put("Idempotency-Key", key);
        return api.send("POST", path, tokens.token(sub), headers, body);
    }

    protected static Map<String, Object> accept(int proposalVersion) {
        return new HashMap<>(Map.of("proposalVersion", proposalVersion, "decision", "accept"));
    }

    /** A draft for a new triaged plumbing issue, sent to {@code CONTRACTOR} (Plumbing Co.): version 2. */
    protected String sentIntervention() {
        String interventionId = (String) draft(ADMIN_A, triagedPlumbingIssue(), PLUMBING_CO).json().get("interventionId");
        Api.Response approved = approve(ADMIN_A, interventionId, 1, 1);
        if (approved.status() != 200) {
            throw new IllegalStateException("approval failed: " + approved.status());
        }
        return interventionId;
    }

    /** The outbox rows' CloudEvents payloads, oldest first. */
    protected List<Map<String, Object>> outboxEvents() {
        return jdbc.sql("SELECT payload FROM outbox ORDER BY created_at, id").query(String.class).list().stream()
                .map(Api::parse)
                .toList();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
