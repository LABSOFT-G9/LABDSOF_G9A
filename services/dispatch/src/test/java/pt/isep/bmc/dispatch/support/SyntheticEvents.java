package pt.isep.bmc.dispatch.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import pt.isep.bmc.dispatch.adapters.in.messaging.IssueEvents;
import pt.isep.bmc.dispatch.application.port.in.ApplyIssueStateChangedUseCase;
import pt.isep.bmc.dispatch.application.port.in.ApplyMembershipChangeUseCase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue's events as Issue would publish them ({@code data} in the proto3
 * JSON mapping, technical design annex A 1.3), applied through the same
 * mapping and use cases the RabbitMQ handlers will call (EN-02-02).
 */
public final class SyntheticEvents {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApplyIssueStateChangedUseCase issues;
    private final ApplyMembershipChangeUseCase memberships;

    public SyntheticEvents(ApplyIssueStateChangedUseCase issues, ApplyMembershipChangeUseCase memberships) {
        this.issues = issues;
        this.memberships = memberships;
    }

    /** {@code IssueStateChanged}; {@code category} {@code null} leaves the optional field out. */
    public boolean issueStateChanged(
            String issueId, String buildingId, String state, long version, boolean dispatchable, String category) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("issueId", issueId);
        data.put("buildingId", buildingId);
        data.put("state", state);
        data.put("version", version);
        data.put("dispatchable", dispatchable);
        if (category != null) {
            data.put("category", category);
            data.put("urgency", "high");
        }
        data.put("areaLabel", "Garage level -1");
        return issues.apply(IssueEvents.issueStateChanged(json(data), Instant.now()));
    }

    public boolean granted(String membershipId, String userId, String buildingId, String role, long version) {
        return membership(IssueEvents.MEMBERSHIP_GRANTED, membershipId, userId, buildingId, role, version);
    }

    public boolean revoked(String membershipId, String userId, String buildingId, String role, long version) {
        return membership(IssueEvents.MEMBERSHIP_REVOKED, membershipId, userId, buildingId, role, version);
    }

    private boolean membership(
            String type, String membershipId, String userId, String buildingId, String role, long version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("membershipId", membershipId);
        data.put("userId", userId);
        data.put("buildingId", buildingId);
        data.put("role", role);
        data.put("version", version);
        return memberships.apply(IssueEvents.membership(type, json(data), Instant.now()));
    }

    private static JsonNode json(Map<String, Object> data) {
        return JSON.valueToTree(data);
    }
}
