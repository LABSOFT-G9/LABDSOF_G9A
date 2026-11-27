package pt.isep.bmc.dispatch.adapters.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.projection.MembershipId;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.projection.ProjectedMembership;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import tools.jackson.databind.JsonNode;

/**
 * Reads the {@code data} of Issue's events into Dispatch's projection rows.
 * {@code data} is the proto3 JSON mapping of {@code contracts/proto/bmc/issue/v1}
 * (technical design 4.4): camelCase names, and absent optional fields left
 * out. Unknown fields are ignored, as the contracts README asks of consumers.
 *
 * <p>The RabbitMQ handlers that call this arrive with the messaging starter
 * (EN-02-02). Until then the integration tests feed synthetic events through
 * it.
 */
public final class IssueEvents {

    public static final String ISSUE_STATE_CHANGED = "IssueStateChanged";
    public static final String MEMBERSHIP_GRANTED = "MembershipGranted";
    public static final String MEMBERSHIP_REVOKED = "MembershipRevoked";

    private IssueEvents() {
    }

    /** @param appliedAt when Dispatch applies the event */
    public static ProjectedIssue issueStateChanged(JsonNode data, Instant appliedAt) {
        return new ProjectedIssue(
                new IssueId(uuid(data, "issueId")),
                new BuildingId(uuid(data, "buildingId")),
                text(data, "state"),
                version(data),
                data.path("dispatchable").asBoolean(false),
                optional(data, "category"),
                optional(data, "urgency"),
                text(data, "areaLabel"),
                optional(data, "publishedSummaryId"),
                appliedAt);
    }

    /** {@code MembershipGranted} makes the membership active; {@code MembershipRevoked} makes it inactive. */
    public static ProjectedMembership membership(String eventType, JsonNode data, Instant appliedAt) {
        boolean active = switch (eventType) {
            case MEMBERSHIP_GRANTED -> true;
            case MEMBERSHIP_REVOKED -> false;
            default -> throw new IllegalArgumentException("not a membership event: " + eventType);
        };
        return new ProjectedMembership(
                new MembershipId(uuid(data, "membershipId")),
                new UserId(text(data, "userId")),
                new BuildingId(uuid(data, "buildingId")),
                text(data, "role"),
                active,
                version(data),
                appliedAt);
    }

    private static String text(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("event data lacks " + field);
        }
        return value.asString();
    }

    private static Optional<String> optional(JsonNode data, String field) {
        JsonNode value = data.get(field);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value.asString());
    }

    private static UUID uuid(JsonNode data, String field) {
        return UUID.fromString(text(data, field));
    }

    /** proto3 JSON writes {@code int32} as a number. */
    private static long version(JsonNode data) {
        JsonNode value = data.get("version");
        if (value == null || !value.canConvertToLong() || value.asLong() < 1) {
            throw new IllegalArgumentException("event data lacks a version");
        }
        return value.asLong();
    }
}
