package pt.isep.bmc.notification.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.notification.support.Fixtures.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import pt.isep.bmc.notification.support.IntegrationTestSupport;
import pt.isep.bmc.notification.support.SyntheticRequests;

/**
 * Payloads never reach the logs, even with DEBUG logging of the web and JDBC
 * layers (which print requests, responses and SQL): neither a valid label nor
 * text a producer wrongly sent.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.pt.isep.bmc=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG"
})
class LogPrivacyIT extends IntegrationTestSupport {

    static final String MARKER = "PrivateMarker Flat 2B tenant Jane Synthetic";

    @Test
    void payloadsNeverReachTheLogs(CapturedOutput output) {
        SyntheticRequests.Request valid = requests.request("contractor.request_sent",
                Map.of("interventionId", UUID.randomUUID().toString(), "areaLabel", MARKER), CONTRACTOR);
        requests.deliver(valid);
        requests.deliver(valid);

        Map<String, String> wrong = new LinkedHashMap<>();
        wrong.put("issueId", UUID.randomUUID().toString());
        wrong.put("reportText", MARKER);
        wrong.put(MARKER, "x");
        requests.deliver(requests.request("issue.triaged", wrong, RESIDENT_A1));

        api.get("/notifications", tokens.token(CONTRACTOR));

        assertThat(output.getAll()).contains("/notifications", valid.eventId())
                .doesNotContain("PrivateMarker", "Jane Synthetic");
    }
}
