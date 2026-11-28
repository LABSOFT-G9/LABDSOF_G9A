package pt.isep.bmc.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.notification.domain.notification.NotificationRequest;
import pt.isep.bmc.notification.domain.notification.Priority;
import pt.isep.bmc.notification.domain.shared.FieldViolation;
import pt.isep.bmc.notification.domain.shared.UserId;
import pt.isep.bmc.notification.domain.shared.ValidationException;

class NotificationRequestTest {

    static final String ID = "0199f2a1-0000-7000-8000-000000009001";
    static final String EVENT = "0199f2a1-0000-7000-8000-000000006003";
    static final String ISSUE = "0199f2a1-0000-7000-8000-000000002001";

    @Test
    void aValidRequestKeepsItsRecipientsOnceAndDefaultsThePriority() {
        NotificationRequest request = NotificationRequest.of(ID, EVENT, List.of("user-1", "user-2", "user-1"),
                "issue.triaged", Map.of("issueId", ISSUE, "category", "plumbing"), "");
        assertThat(request.recipients()).containsExactlyInAnyOrder(new UserId("user-1"), new UserId("user-2"));
        assertThat(request.priority()).isEqualTo(Priority.NORMAL);
        assertThat(request.template().key()).isEqualTo("issue.triaged");
    }

    @Test
    void everyInvalidFieldIsReportedAtOnce() {
        assertThat(violations(() -> NotificationRequest.of("not-a-uuid", "", List.of(), "issue.triaged",
                Map.of("category", "Plumbing!"), "urgent")))
                .containsExactlyInAnyOrder(
                        new FieldViolation("notificationId", "invalid_format"),
                        new FieldViolation("sourceEventId", "required"),
                        new FieldViolation("recipientUserIds", "required"),
                        new FieldViolation("params.issueId", "required"),
                        new FieldViolation("params.category", "invalid_format"),
                        new FieldViolation("priority", "invalid_value"));
    }

    @Test
    void anUnknownTemplateIsRefused() {
        assertThat(violations(() -> NotificationRequest.of(ID, EVENT, List.of("u"), "issue.gossip", Map.of(), null)))
                .containsExactly(new FieldViolation("templateKey", "unknown_template"));
    }

    @Test
    void aParameterTheTemplateDoesNotListIsRefusedSoNoFreeTextGetsIn() {
        Map<String, String> params = Map.of("issueId", ISSUE, "description", "Flat 2B: the tenant is away");
        assertThat(violations(() -> NotificationRequest.of(ID, EVENT, List.of("u"), "issue.triaged", params, null)))
                .containsExactly(new FieldViolation("params.description", "not_allowed"));
    }

    @Test
    void aParameterNameThatIsNotAnIdentifierIsNeverEchoed() {
        Map<String, String> params = new HashMap<>(Map.of("issueId", ISSUE));
        params.put("Jane in 2B is away", "x");
        List<FieldViolation> violations =
                violations(() -> NotificationRequest.of(ID, EVENT, List.of("u"), "issue.triaged", params, null));
        assertThat(violations).containsExactly(new FieldViolation("params", "invalid_name"));
        assertThat(violations.toString()).doesNotContain("Jane");
    }

    @Test
    void idsLabelsAndTimesAreCheckedByKind() {
        assertThat(violations(() -> NotificationRequest.of(ID, EVENT, List.of("u"), "booking.confirmed",
                Map.of("issueId", "42", "interventionId", ISSUE, "windowStart", "tomorrow"), "high")))
                .containsExactlyInAnyOrder(
                        new FieldViolation("params.issueId", "invalid_format"),
                        new FieldViolation("params.windowStart", "invalid_format"));
        assertThat(violations(() -> NotificationRequest.of(ID, EVENT, List.of("u"), "contractor.request_sent",
                Map.of("interventionId", ISSUE, "areaLabel", "x".repeat(121)), null)))
                .containsExactly(new FieldViolation("params.areaLabel", "too_long"));
        assertThat(violations(() -> NotificationRequest.of(ID, EVENT, List.of("u"), "contractor.request_sent",
                Map.of("interventionId", ISSUE, "areaLabel", "Garage\nlevel -1"), null)))
                .containsExactly(new FieldViolation("params.areaLabel", "invalid_format"));
    }

    @Test
    void recipientsAreBounded() {
        List<String> many = IntStream.range(0, NotificationRequest.MAX_RECIPIENTS + 1).mapToObj(i -> "user-" + i).toList();
        assertThat(violations(() -> NotificationRequest.of(ID, EVENT, many, "issue.cancelled",
                Map.of("issueId", ISSUE), null)))
                .containsExactly(new FieldViolation("recipientUserIds", "too_many"));
        assertThat(violations(() -> NotificationRequest.of(ID, EVENT, Collections.singletonList(" "),
                "issue.cancelled", Map.of("issueId", ISSUE), null)))
                .containsExactly(new FieldViolation("recipientUserIds", "invalid_format"));
    }

    @Test
    void anUpperCaseUuidIsNotTheSameIdentifier() {
        assertThat(violations(() -> NotificationRequest.of(ID.toUpperCase(), EVENT, List.of("u"), "issue.cancelled",
                Map.of("issueId", ISSUE), null)))
                .containsExactly(new FieldViolation("notificationId", "invalid_format"));
        assertThat(NotificationRequest.of(ID, EVENT, List.of("u"), "issue.cancelled", Map.of("issueId", ISSUE), null)
                .notificationId()).isEqualTo(UUID.fromString(ID));
    }

    static List<FieldViolation> violations(Runnable build) {
        try {
            build.run();
        } catch (ValidationException e) {
            return e.violations();
        }
        throw new AssertionError("expected a ValidationException");
    }
}
