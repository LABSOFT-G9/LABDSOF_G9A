package pt.isep.bmc.notification.support;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler;

/**
 * Boots the whole service on a random port against a real MySQL 8.4 (Flyway
 * migrations included) and the test JWKS. Every test starts from an empty
 * inbox and feeds synthetic {@code NotificationRequested} events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    /** Tables emptied before each test. */
    private static final String[] TABLES = {"notifications", "deliveries"};

    @Autowired
    protected Environment environment;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected NotificationRequestedHandler handler;

    protected final TestTokens tokens = TestTokens.get();
    protected Api api;
    protected SyntheticRequests requests;

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
        registry.add("bmc.notification.consumer.retry-delay", () -> "0s");
        registry.add("management.server.port", () -> "0");
    }

    @BeforeEach
    void resetData() {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        api = new Api(environment.getRequiredProperty("local.server.port", Integer.class));
        requests = new SyntheticRequests(handler);
    }

    /** A triage update about a new issue, for the given recipients. */
    protected SyntheticRequests.Request triaged(String... recipients) {
        return requests.request("issue.triaged",
                Map.of("issueId", UUID.randomUUID().toString(), "category", "plumbing", "urgency", "high"),
                recipients);
    }

    protected long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
