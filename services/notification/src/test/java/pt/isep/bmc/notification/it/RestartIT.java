package pt.isep.bmc.notification.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.notification.support.Fixtures.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.notification.NotificationApplication;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler;
import pt.isep.bmc.notification.adapters.in.messaging.NotificationRequestedHandler.Outcome;
import pt.isep.bmc.notification.support.Api;
import pt.isep.bmc.notification.support.SyntheticRequests;
import pt.isep.bmc.notification.support.TestDatabase;
import pt.isep.bmc.notification.support.TestTokens;

/** US-10-01: notifications and the delivery log survive a restart, so a replay after it adds nothing (NFR-09). */
class RestartIT {

    @Test
    void aNotificationSurvivesARestartAndItsReplayAfterwardsIsADuplicate() {
        MySQLContainer mysql = TestDatabase.newContainer();
        mysql.start();
        try {
            TestTokens tokens = TestTokens.get();
            SyntheticRequests.Request request;
            try (ConfigurableApplicationContext first = start(mysql)) {
                SyntheticRequests requests = new SyntheticRequests(first.getBean(NotificationRequestedHandler.class));
                request = requests.request("issue.resolved", Map.of("issueId", UUID.randomUUID().toString()),
                        RESIDENT_A1);
                assertThat(requests.deliver(request)).isEqualTo(Outcome.DELIVERED);
            }

            try (ConfigurableApplicationContext second = start(mysql)) {
                SyntheticRequests requests = new SyntheticRequests(second.getBean(NotificationRequestedHandler.class));
                assertThat(requests.deliver(request)).isEqualTo(Outcome.DUPLICATE);
                Api api = new Api(second.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
                assertThat(Api.list(api.get("/notifications", tokens.token(RESIDENT_A1)).json().get("items")))
                        .singleElement()
                        .satisfies(item -> assertThat(Api.map(item)).containsEntry("templateKey", "issue.resolved"));
            }
        } finally {
            mysql.stop();
        }
    }

    static ConfigurableApplicationContext start(MySQLContainer mysql) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", "0");
        properties.put("management.server.port", "0");
        properties.put("spring.datasource.url",
                mysql.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("bmc.security.issuer-uri", TestTokens.ISSUER);
        properties.put("bmc.security.jwk-set-uri", TestTokens.get().jwksUri());
        // Command-line arguments: default properties would lose to application.yaml.
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(NotificationApplication.class).run(args);
    }
}
