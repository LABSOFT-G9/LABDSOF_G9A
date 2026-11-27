package pt.isep.bmc.dispatch.application;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.projection.MembershipId;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.projection.ProjectedMembership;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Synthetic values for the application tests: Administrator A administers
 * Building A, Administrator B Building B, Resident A1 lives in Building A.
 * Two plumbers cover Building A; the electrician covers it too.
 */
final class ApplicationFixtures {

    static final Instant NOW = Instant.parse("2026-11-26T10:00:00.123456Z");
    static final String WINDOW_START = "2026-11-28T09:00:00Z";
    static final String WINDOW_END = "2026-11-28T12:00:00Z";
    static final String SCOPE = "Leak above garage light fitting, level -1. Access via management.";
    static final String TERMS = "Inspect and repair; report cost if known.";

    static final BuildingId BUILDING_A = new BuildingId(UUID.fromString("0199f2a0-0000-7000-8000-00000000b001"));
    static final BuildingId BUILDING_B = new BuildingId(UUID.fromString("0199f2a0-0000-7000-8000-00000000b002"));
    static final UserId RESIDENT_A1 = new UserId("0199f2a0-0000-7000-8000-000000000101");
    static final UserId ADMIN_A = new UserId("0199f2a0-0000-7000-8000-000000000201");
    static final UserId ADMIN_B = new UserId("0199f2a0-0000-7000-8000-000000000202");
    static final MembershipId MEMBERSHIP_ADMIN_A = membershipId(4);

    static final Contractor PLUMBER = contractor(1, "Synthetic Plumbing Co.", "plumbing", true, BUILDING_A);
    static final Contractor OTHER_PLUMBER = contractor(3, "Synthetic Aqua Plumbers", "plumbing", true, BUILDING_A);
    static final Contractor ELECTRICIAN = contractor(6, "Synthetic Electrical Ltd.", "electrical", true, BUILDING_A);

    private ApplicationFixtures() {
    }

    static MembershipId membershipId(int n) {
        return new MembershipId(UUID.fromString("0199f2a0-0000-7000-8000-00000000d%03d".formatted(n)));
    }

    static Contractor contractor(int n, String name, String category, boolean active, BuildingId... coverage) {
        return new Contractor(new ContractorId(UUID.fromString("0199f2a0-0000-7000-8000-00000000e%03d".formatted(n))),
                Optional.empty(), name, Set.of(category), Set.of(coverage), active, true);
    }

    static ProjectedIssue issue(IssueId id, String state, long version, boolean dispatchable) {
        return new ProjectedIssue(id, BUILDING_A, state, version, dispatchable, Optional.of("plumbing"),
                Optional.of("high"), "Garage level -1", Optional.empty(), NOW.minusSeconds(60));
    }

    static ProjectedIssue triagedPlumbing(IssueId id) {
        return issue(id, "triaged", 3, true);
    }

    static ProjectedMembership membership(
            MembershipId id, UserId user, BuildingId building, String role, boolean active, long version) {
        return new ProjectedMembership(id, user, building, role, active, version, NOW.minusSeconds(3600));
    }

    /** The ports with the three memberships and the three contractors. */
    static InMemoryPorts ports() {
        InMemoryPorts ports = new InMemoryPorts();
        ports.memberships.put(MEMBERSHIP_ADMIN_A,
                membership(MEMBERSHIP_ADMIN_A, ADMIN_A, BUILDING_A, "administrator", true, 1));
        ports.memberships.put(membershipId(5), membership(membershipId(5), ADMIN_B, BUILDING_B, "administrator", true, 1));
        ports.memberships.put(membershipId(1), membership(membershipId(1), RESIDENT_A1, BUILDING_A, "resident", true, 1));
        for (Contractor contractor : new Contractor[] {PLUMBER, OTHER_PLUMBER, ELECTRICIAN}) {
            ports.contractors.put(contractor.id(), contractor);
        }
        return ports;
    }

    static IssueId newIssueId() {
        return new IssueId(UUID.randomUUID());
    }
}
