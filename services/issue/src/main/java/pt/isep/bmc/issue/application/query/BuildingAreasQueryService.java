package pt.isep.bmc.issue.application.query;

import java.time.Clock;
import java.util.List;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.port.in.ListBuildingAreasQuery;
import pt.isep.bmc.issue.application.port.out.BuildingRepository;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.UserId;

/** Areas a member can pick in the report form; non-members get not found. */
public final class BuildingAreasQueryService implements ListBuildingAreasQuery {

    private final TransactionRunner transactions;
    private final BuildingRepository buildings;
    private final MembershipRepository memberships;
    private final Clock clock;

    public BuildingAreasQueryService(
            TransactionRunner transactions, BuildingRepository buildings, MembershipRepository memberships, Clock clock) {
        this.transactions = transactions;
        this.buildings = buildings;
        this.memberships = memberships;
        this.clock = clock;
    }

    @Override
    public List<Area> list(UserId actor, BuildingId building) {
        return transactions.readOnly(() -> {
            BuildingAccess access = BuildingAccess.of(actor, memberships.findByUser(actor), clock.instant());
            if (!access.isMemberOf(building)) {
                throw new NotFoundException();
            }
            return buildings.findAreas(building);
        });
    }
}
