package pt.isep.bmc.issue.application.query;

import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.MembershipId;
import pt.isep.bmc.issue.domain.membership.MembershipRole;

public record MembershipView(MembershipId membershipId, BuildingId buildingId, String buildingName, MembershipRole role) {
}
