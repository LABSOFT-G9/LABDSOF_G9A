package pt.isep.bmc.notification.adapters.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import pt.isep.bmc.notification.domain.notification.NotificationRequest;
import pt.isep.bmc.notification.domain.notification.Priority;
import pt.isep.bmc.notification.domain.shared.FieldViolation;
import pt.isep.bmc.notification.domain.shared.ValidationException;
import tools.jackson.databind.json.JsonMapper;

/** {@code data} as the proto3 JSON mapping writes it (contracts README). */
class NotificationRequestedEventsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void readsTheProto3JsonMappingAndIgnoresUnknownFields() {
        NotificationRequest request = NotificationRequestedEvents.parse(JSON.readTree("""
                {"notificationId": "0199f2a1-0000-7000-8000-000000009001",
                 "sourceEventId": "0199f2a1-0000-7000-8000-000000006003",
                 "recipientUserIds": ["0199f2a0-0000-7000-8000-000000000101"],
                 "templateKey": "booking.confirmed",
                 "params": {"issueId": "0199f2a1-0000-7000-8000-000000002001",
                            "interventionId": "0199f2a1-0000-7000-8000-000000003001",
                            "windowStart": "2099-11-05T09:00:00Z"},
                 "priority": "high",
                 "aFieldAddedLater": 1}
                """));
        assertThat(request.priority()).isEqualTo(Priority.HIGH);
        assertThat(request.params()).containsKeys("issueId", "interventionId", "windowStart");
    }

    @Test
    void absentOptionalFieldsAreDefaults() {
        NotificationRequest request = NotificationRequestedEvents.parse(JSON.readTree("""
                {"notificationId": "0199f2a1-0000-7000-8000-000000009001",
                 "sourceEventId": "e-1",
                 "recipientUserIds": ["u"],
                 "templateKey": "contractor.booking_cancelled",
                 "params": {"interventionId": "0199f2a1-0000-7000-8000-000000003001"}}
                """));
        assertThat(request.priority()).isEqualTo(Priority.NORMAL);
    }

    @Test
    void wrongJsonTypesAreViolations() {
        ValidationException invalid = catchThrowableOfType(ValidationException.class,
                () -> NotificationRequestedEvents.parse(JSON.readTree("""
                        {"notificationId": 7, "sourceEventId": "e-1", "recipientUserIds": "u",
                         "templateKey": "issue.resolved", "params": {"issueId": 42}}
                        """)));
        assertThat(invalid.violations()).contains(
                new FieldViolation("notificationId", "invalid_type"),
                new FieldViolation("recipientUserIds", "invalid_type"),
                new FieldViolation("params", "invalid_type"));
    }

    @Test
    void anInvitationNeedsTheEmailChannelWhichIsNotBuiltYet() {
        ValidationException invalid = catchThrowableOfType(ValidationException.class,
                () -> NotificationRequestedEvents.parse(JSON.readTree("""
                        {"notificationId": "0199f2a1-0000-7000-8000-000000009001", "sourceEventId": "e-1",
                         "templateKey": "invitation", "recipientAddress": "someone@example.test",
                         "inviteLink": "https://bmc.example/invite#t=secret"}
                        """)));
        assertThat(invalid.violations()).contains(new FieldViolation("recipientAddress", "unsupported_channel"));
        assertThat(invalid.violations().toString()).doesNotContain("someone@", "secret");
    }

    @Test
    void dataThatIsNotAnObjectIsRefused() {
        assertThat(catchThrowableOfType(ValidationException.class,
                () -> NotificationRequestedEvents.parse(JSON.readTree("[1]"))).violations())
                .containsExactly(new FieldViolation("data", "invalid_type"));
    }
}
