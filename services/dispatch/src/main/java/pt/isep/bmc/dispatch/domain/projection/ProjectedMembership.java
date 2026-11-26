package pt.isep.bmc.dispatch.domain.projection;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Dispatch's copy of a building membership, written only from Issue's {@code
 * MembershipGranted} ({@code active}) and {@code MembershipRevoked} (not
 * active) events (technical design 2.2 and 4.3).
 *
 * @param role Issue's role value: {@code resident} or {@code administrator}
 */
public record ProjectedMembership(
        MembershipId membershipId,
        UserId userId,
        BuildingId buildingId,
        String role,
        boolean active,
        long version,
        Instant appliedAt) {

    public static final String ADMINISTRATOR = "administrator";

    public ProjectedMembership {
        Objects.requireNonNull(membershipId, "membershipId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (version < 1) {
            throw new IllegalArgumentException("membership version must be at least 1");
        }
    }

    /**
     * Applied by the membership's version: a replayed {@code MembershipGranted}
     * cannot undo a later {@code MembershipRevoked} (technical design 4.3 step 3).
     */
    public boolean replaces(ProjectedMembership stored) {
        return version > stored.version;
    }

    public boolean isActiveAdministrator() {
        return active && ADMINISTRATOR.equals(role);
    }
}
