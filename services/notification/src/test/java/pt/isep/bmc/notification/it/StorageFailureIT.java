package pt.isep.bmc.notification.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.notification.support.Fixtures.*;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler.Outcome;
import pt.isep.bmc.notification.support.Api;
import pt.isep.bmc.notification.support.IntegrationTestSupport;
import pt.isep.bmc.notification.support.SyntheticRequests;
import pt.isep.bmc.notification.support.TestDatabase;
import pt.isep.bmc.notification.support.TestTokens;

/**
 * The notification database goes down: a request is tried a limited number
 * of times, then given up with its event and correlation IDs in the log and
 * the metric, never its payload. Reading the inbox answers {@code 503}. Uses
 * its own MySQL, stopped mid-test, as Dispatch's test does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.hikari.connection-timeout=2000", "bmc.notification.consumer.max-attempts=3"})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class StorageFailureIT {

    static final MySQLContainer MYSQL = TestDatabase.newContainer();

    static {
        MYSQL.start();
    }

    @Autowired
    Environment environment;

    @Autowired
    NotificationRequestedHandler handler;

    @Autowired
    MeterRegistry meters;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry, MYSQL);
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    void retriesAreLimitedAndTheirEndIsRecordedWithoutThePayload(CapturedOutput output) {
        SyntheticRequests requests = new SyntheticRequests(handler);
        Api api = new Api(environment.getRequiredProperty("local.server.port", Integer.class));
        String token = TestTokens.get().token(RESIDENT_A1);
        assertThat(requests.deliver(requests.request("issue.resolved",
                Map.of("issueId", UUID.randomUUID().toString()), RESIDENT_A1))).isEqualTo(Outcome.DELIVERED);
        assertThat(api.get("/notifications", token).status()).isEqualTo(200);

        MYSQL.stop();

        SyntheticRequests.Request request = requests.request("contractor.request_sent",
                Map.of("interventionId", UUID.randomUUID().toString(), "areaLabel", "PrivateMarker garage"),
                CONTRACTOR);
        assertThat(requests.deliver(request)).isEqualTo(Outcome.EXHAUSTED);
        assertThat(meters.counter("bmc.notification.requests", "outcome", "exhausted").count()).isEqualTo(1);
        assertThat(output.getAll())
                .contains("not stored after 3 attempts", request.eventId(), request.correlationId())
                .doesNotContain("PrivateMarker");

        var read = api.get("/notifications", token);
        assertThat(read.status()).isEqualTo(503);
        assertThat(read.json()).containsEntry("code", "DEPENDENCY_UNAVAILABLE");
        assertThat(read.header("Retry-After")).isEqualTo("5");
    }
}
