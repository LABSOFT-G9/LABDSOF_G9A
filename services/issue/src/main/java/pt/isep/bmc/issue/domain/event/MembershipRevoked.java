package pt.isep.bmc.issue.domain.event;

import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.MembershipId;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * A user no longer holds a role in a building (technical design 4.4). Feeds Dispatch's
 * administrator projection, which applies it by the membership's version.
 */
public record MembershipRevoked(
        MembershipId membershipId,
        UserId userId,
        BuildingId buildingId,
        MembershipRole role,
        long membershipVersion,
        Instant occurredAt)
        implements DomainEvent {

    @Override
    public String type() {
        return "MembershipRevoked";
    }

    @Override
    public UUID aggregateId() {
        return membershipId.value();
    }

    @Override
    public long aggregateVersion() {
        return membershipVersion;
    }
}
