package pt.isep.bmc.issue.it;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.issue.support.Fixtures.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;
import pt.isep.bmc.issue.IssueApplication;
import pt.isep.bmc.issue.application.command.RecordTriageSuggestionCommand;
import pt.isep.bmc.issue.application.command.SuggestionResult;
import pt.isep.bmc.issue.application.port.in.RecordTriageSuggestionUseCase;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.SuggestionInput;
import pt.isep.bmc.issue.support.Api;
import pt.isep.bmc.issue.support.TestDatabase;
import pt.isep.bmc.issue.support.TestTokens;

/** US-01: the author retrieves the report after a backend restart (NFR-09). */
class RestartIT {

    @Test
    void reportSurvivesARestart() throws Exception {
        MySQLContainer mysql = TestDatabase.shared();
        TestTokens tokens = TestTokens.get();
        String text = "Restart check: water dripping in the garage, level -1.";
        String reportId;

        try (ConfigurableApplicationContext first = start(mysql)) {
            try (var connection = first.getBean(javax.sql.DataSource.class).getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us01-fixtures.sql"));
            }
            var created = api(first).post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                    Map.of("buildingId", BUILDING_A, "areaId", AREA_A_GARAGE, "text", text));
            assertThat(created.status()).isEqualTo(201);
            reportId = (String) created.json().get("reportId");
        }

        try (ConfigurableApplicationContext second = start(mysql)) {
            var read = api(second).get("/reports/" + reportId, tokens.token(RESIDENT_A1));
            assertThat(read.status()).isEqualTo(200);
            assertThat(read.json()).containsEntry("text", text)
                    .containsEntry("issue", Map.of("state", "received", "version", 1));
        }
    }

    /** US-02: the decision, its reason and the history are still there after a restart. */
    @Test
    void triageDecisionAndHistorySurviveARestart() throws Exception {
        MySQLContainer mysql = TestDatabase.shared();
        TestTokens tokens = TestTokens.get();
        String reason = "Restart check: water near an electrical fitting";
        String issueId;

        try (ConfigurableApplicationContext first = start(mysql)) {
            try (var connection = first.getBean(javax.sql.DataSource.class).getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us01-fixtures.sql"));
            }
            var created = api(first).post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                    Map.of("buildingId", BUILDING_A, "areaId", AREA_A_GARAGE, "text", "Restart check for triage, garage."));
            issueId = (String) created.json().get("issueId");
            var triaged = api(first).postWithHeaders("/issues/" + issueId + "/triage", tokens.token(ADMIN_A),
                    Map.of("If-Match", "\"1\"", "Idempotency-Key", UUID.randomUUID().toString()),
                    Map.of("category", "plumbing", "urgency", "high", "reason", reason));
            assertThat(triaged.status()).isEqualTo(200);
        }

        try (ConfigurableApplicationContext second = start(mysql)) {
            var issue = api(second).get("/issues/" + issueId, tokens.token(ADMIN_A));
            assertThat(issue.status()).isEqualTo(200);
            assertThat(issue.json()).containsEntry("state", "triaged").containsEntry("version", 2);
            assertThat((Map<?, ?>) issue.json().get("triage")).extracting("category", "urgency", "reason")
                    .containsExactly("plumbing", "high", reason);
            var history = api(second).get("/issues/" + issueId + "/history", tokens.token(ADMIN_A));
            assertThat((java.util.List<?>) history.json().get("items")).hasSize(2);
            // The next decision continues from the stored version.
            var next = api(second).postWithHeaders("/issues/" + issueId + "/triage", tokens.token(ADMIN_A),
                    Map.of("If-Match", "\"2\"", "Idempotency-Key", UUID.randomUUID().toString()),
                    Map.of("category", "electrical", "urgency", "critical", "reason", reason));
            assertThat(next.status()).isEqualTo(200);
            assertThat(next.json()).containsEntry("version", 3);
        }
    }

    /** US-03: the suggestion and its history entry are still there after a restart, and the same event is not recorded twice. */
    @Test
    void suggestionSurvivesARestart() throws Exception {
        MySQLContainer mysql = TestDatabase.shared();
        TestTokens tokens = TestTokens.get();
        String rationale = "Restart check: the report mentions a leak.";
        UUID eventId = UUID.randomUUID();
        String issueId;
        try (ConfigurableApplicationContext first = start(mysql)) {
            try (var connection = first.getBean(javax.sql.DataSource.class).getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us01-fixtures.sql"));
            }
            var created = api(first).post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                    Map.of("buildingId", BUILDING_A, "areaId", AREA_A_GARAGE, "text", "Restart check for suggestions, garage."));
            issueId = (String) created.json().get("issueId");
            assertThat(suggest(first, issueId, eventId, rationale)).isEqualTo(SuggestionResult.APPLIED);
        }
        try (ConfigurableApplicationContext second = start(mysql)) {
            // The broker delivers the event again after the restart.
            assertThat(suggest(second, issueId, eventId, rationale)).isEqualTo(SuggestionResult.DUPLICATE);

            var issue = api(second).get("/issues/" + issueId, tokens.token(ADMIN_A));
            assertThat(issue.status()).isEqualTo(200);
            assertThat(issue.json()).containsEntry("state", "in_triage").containsEntry("version", 2);
            assertThat((Map<?, ?>) issue.json().get("suggestion")).extracting("category", "urgency", "origin", "rationale")
                    .containsExactly("plumbing", "high", "rules", rationale);
            var history = api(second).get("/issues/" + issueId + "/history", tokens.token(ADMIN_A));
            assertThat((java.util.List<?>) history.json().get("items")).hasSize(2);

            // A decision continues from the stored version.
            var decided = api(second).postWithHeaders("/issues/" + issueId + "/triage", tokens.token(ADMIN_A),
                    Map.of("If-Match", "\"2\"", "Idempotency-Key", UUID.randomUUID().toString()),
                    Map.of("category", "plumbing", "urgency", "high", "reason", "Confirmed on site"));
            assertThat(decided.status()).isEqualTo(200);
            assertThat(decided.json()).containsEntry("version", 3);
        }
    }

    private static SuggestionResult suggest(ConfigurableApplicationContext context, String issueId, UUID eventId, String rationale) {
        return context.getBean(RecordTriageSuggestionUseCase.class).record(new RecordTriageSuggestionCommand(
                new IssueId(UUID.fromString(issueId)),
                new SuggestionInput(eventId, 1, "plumbing", "high", rationale, false, "rules", null, null, "rules-v1", null, null),
                "corr-restart-it"));
    }

    /**
     * US-14: alerts are still there after a restart, and a check that was left
     * pending is completed by the next start, once.
     */
    @Test
    @SuppressWarnings("unchecked")
    void criticalAlertsSurviveARestartAndAPendingCheckIsCompletedAtTheNextStart() throws Exception {
        MySQLContainer mysql = TestDatabase.shared();
        TestTokens tokens = TestTokens.get();
        String checked;
        String leftPending;
        try (ConfigurableApplicationContext first = start(mysql)) {
            try (var connection = first.getBean(javax.sql.DataSource.class).getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us01-fixtures.sql"));
            }
            checked = (String) api(first).post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                    Map.of("buildingId", BUILDING_A, "areaId", AREA_A_GARAGE,
                            "text", "Restart check: there is smoke in the garage.")).json().get("issueId");
            leftPending = (String) api(first).post("/reports", tokens.token(RESIDENT_A1),
                    UUID.randomUUID().toString(), Map.of("buildingId", BUILDING_A, "areaId", AREA_A_HALL,
                            "text", "Restart check: a strong smell of gas in the hall.")).json().get("issueId");
            // As if the rule set had failed for the second report: stored, pending, no signal.
            var jdbc = first.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
            jdbc.sql("DELETE FROM issue_critical_signals WHERE issue_id = :id").param("id", leftPending).update();
            jdbc.sql("DELETE FROM issue_history WHERE issue_id = :id AND type = 'critical_alert_raised'")
                    .param("id", leftPending).update();
            jdbc.sql("UPDATE issues SET critical_check_status = 'pending', critical_rules_version = NULL"
                    + " WHERE id = :id").param("id", leftPending).update();
            var pending = (Map<String, Object>) api(first).get("/issues/" + leftPending, tokens.token(ADMIN_A))
                    .json().get("criticalCheck");
            assertThat(pending).containsEntry("status", "pending");
            assertThat((java.util.List<?>) pending.get("signals")).isEmpty();
        }
        for (int start = 0; start < 2; start++) {
            try (ConfigurableApplicationContext next = start(mysql)) {
                var kept = (Map<String, Object>) api(next).get("/issues/" + checked, tokens.token(ADMIN_A))
                        .json().get("criticalCheck");
                assertThat(kept).containsEntry("status", "completed")
                        .containsEntry("rulesVersion", "critical-alerts-v1");
                assertThat((java.util.List<Map<String, Object>>) kept.get("signals"))
                        .extracting(signal -> signal.get("code")).containsExactly("smoke_or_fire");

                var issue = api(next).get("/issues/" + leftPending, tokens.token(ADMIN_A));
                // Completed at start, with the alert, on the next version; the second start adds nothing.
                assertThat(issue.json()).containsEntry("state", "received").containsEntry("version", 2);
                var completed = (Map<String, Object>) issue.json().get("criticalCheck");
                assertThat(completed).containsEntry("status", "completed");
                assertThat((java.util.List<Map<String, Object>>) completed.get("signals"))
                        .extracting(signal -> signal.get("code")).containsExactly("gas_smell");
                var history = api(next).get("/issues/" + leftPending + "/history", tokens.token(ADMIN_A));
                assertThat((java.util.List<Map<String, Object>>) history.json().get("items"))
                        .extracting(entry -> entry.get("type"))
                        .containsExactly("report_received", "critical_alert_raised");
            }
        }
    }

    /** US-04: the summary, a resident's impact confirmation and their subscription are still there after a restart. */
    @Test
    void summaryImpactAndSubscriptionSurviveARestart() throws Exception {
        MySQLContainer mysql = TestDatabase.shared();
        TestTokens tokens = TestTokens.get();
        String summary = "Restart check: the garage door does not close fully.";
        String issueId;

        try (ConfigurableApplicationContext first = start(mysql)) {
            try (var connection = first.getBean(javax.sql.DataSource.class).getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("fixtures/us01-fixtures.sql"));
            }
            Api api = api(first);
            var created = api.post("/reports", tokens.token(RESIDENT_A1), UUID.randomUUID().toString(),
                    Map.of("buildingId", BUILDING_A, "areaId", AREA_A_GARAGE, "text", "Restart check for sharing, garage."));
            issueId = (String) created.json().get("issueId");
            var published = api.postWithHeaders("/issues/" + issueId + "/publication", tokens.token(ADMIN_A),
                    Map.of("If-Match", "\"1\"", "Idempotency-Key", UUID.randomUUID().toString()),
                    Map.of("summary", summary));
            assertThat(published.status()).isEqualTo(200);
            assertThat(api.method("PUT", "/issues/" + issueId + "/impact", tokens.token(RESIDENT_A2), null).status())
                    .isEqualTo(200);
            assertThat(api.method("PUT", "/issues/" + issueId + "/subscription", tokens.token(RESIDENT_A2), null)
                    .status()).isEqualTo(200);
        }

        try (ConfigurableApplicationContext second = start(mysql)) {
            var issue = api(second).get("/issues/" + issueId, tokens.token(RESIDENT_A2));
            assertThat(issue.status()).isEqualTo(200);
            assertThat(issue.json()).containsEntry("version", 2).containsEntry("subscribed", true);
            assertThat((Map<?, ?>) issue.json().get("summary")).extracting("text").isEqualTo(summary);
            assertThat(issue.json().get("impact")).isEqualTo(Map.of("count", 1, "mine", true));
            // The other resident's own choices are theirs: nothing confirmed, not subscribed.
            var other = api(second).get("/issues/" + issueId, tokens.token(RESIDENT_A1));
            assertThat(other.json()).containsEntry("subscribed", false);
            assertThat(other.json().get("impact")).isEqualTo(Map.of("count", 1, "mine", false));
        }
    }

    static ConfigurableApplicationContext start(MySQLContainer mysql, String... extraArgs) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", "0");
        properties.put("management.server.port", "0");
        properties.put("spring.datasource.url",
                mysql.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("bmc.security.issuer-uri", TestTokens.ISSUER);
        properties.put("bmc.security.jwk-set-uri", TestTokens.get().jwksUri());
        properties.put("bmc.scheduling.enabled", "false");
        // Command-line arguments: default properties would lose to application.yaml.
        String[] args = java.util.stream.Stream.concat(
                        properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()),
                        java.util.Arrays.stream(extraArgs))
                .toArray(String[]::new);
        return new SpringApplicationBuilder(IssueApplication.class).run(args);
    }

    private static Api api(ConfigurableApplicationContext context) {
        return new Api(context.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
    }
}
