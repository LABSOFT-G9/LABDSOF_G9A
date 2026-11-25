package pt.isep.bmc.issue.domain.membership;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.event.MembershipGranted;
import pt.isep.bmc.issue.domain.event.MembershipRevoked;

/**
 * A user's role in one building (technical design 2.1). Unique per user,
 * building and role; {@code version} increases on every change.
 *
 * @param validUntil end of validity, or {@code null} when open-ended
 */
public record Membership(
        MembershipId id,
        UserId userId,
        BuildingId buildingId,
        MembershipRole role,
        MembershipStatus status,
        Instant validFrom,
        Instant validUntil,
        long version) {

    public Membership {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(validFrom, "validFrom");
    }

    /**
     * The event that tells other services this membership's current state:
     * granted while its status is active, revoked otherwise. Carries the
     * membership's version, so a receiver can apply it in order.
     */
    public DomainEvent currentStateEvent(Instant occurredAt) {
        return status == MembershipStatus.ACTIVE
                ? new MembershipGranted(id, userId, buildingId, role, version, occurredAt)
                : new MembershipRevoked(id, userId, buildingId, role, version, occurredAt);
    }

    /** Active and inside its validity window at {@code now}. */
    public boolean isActiveAt(Instant now) {
        return status == MembershipStatus.ACTIVE
                && !now.isBefore(validFrom)
                && (validUntil == null || now.isBefore(validUntil));
    }
}
