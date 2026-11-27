package pt.isep.bmc.dispatch.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.MembershipId;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.projection.ProjectedMembership;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** Synthetic values for the domain tests. */
final class DomainFixtures {

    static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    static final BuildingId BUILDING_A = building("0199f2a0-0000-7000-8000-00000000b001");
    static final BuildingId BUILDING_B = building("0199f2a0-0000-7000-8000-00000000b002");
    static final UserId ADMIN_A = new UserId("0199f2a0-0000-7000-8000-000000000201");
    static final IssueId ISSUE = new IssueId(UUID.fromString("0199f2a0-0000-7000-8000-00000000f001"));

    private DomainFixtures() {
    }

    static BuildingId building(String id) {
        return new BuildingId(UUID.fromString(id));
    }

    static ContractorId contractorId(int n) {
        return new ContractorId(UUID.fromString("0199f2a0-0000-7000-8000-00000000e%03d".formatted(n)));
    }

    static Contractor contractor(int n, String name, String category, boolean active, BuildingId... coverage) {
        return new Contractor(contractorId(n), Optional.empty(), name, Set.of(category), Set.of(coverage), active, true);
    }

    static ProjectedIssue issue(String state, boolean dispatchable, String category) {
        return new ProjectedIssue(ISSUE, BUILDING_A, state, 3, dispatchable, Optional.ofNullable(category),
                Optional.of("high"), "Garage level -1", Optional.empty(), NOW.minusSeconds(60));
    }

    static ProjectedIssue dispatchablePlumbing() {
        return issue("triaged", true, "plumbing");
    }

    static AdministratorAccess administratorOf(UserId user, BuildingId building) {
        return AdministratorAccess.of(user, List.of(new ProjectedMembership(
                new MembershipId(UUID.randomUUID()), user, building, "administrator", true, 1, NOW)));
    }

    static AdministratorAccess nobody(UserId user) {
        return AdministratorAccess.of(user, List.of());
    }
}
