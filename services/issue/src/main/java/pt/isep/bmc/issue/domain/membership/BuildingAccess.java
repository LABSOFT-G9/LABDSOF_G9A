package pt.isep.bmc.issue.domain.membership;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.report.ReportSource;

/**
 * What one user may do in which building, derived from their memberships at a
 * given time. Implements the Issue rows of the access matrix (security and
 * privacy assessment, section 2):
 *
 * <ul>
 *   <li>create a report: residents of the building, or its administrators on
 *       behalf (source recorded);</li>
 *   <li>raw report text: the author, or administrators of the building. Being a
 *       resident of the same building is not enough.</li>
 * </ul>
 *
 * <p>Only the token's {@code sub} identifies the caller; building roles never
 * come from the token or the client (ADR-005).
 */
public final class BuildingAccess {

    private final UserId userId;
    private final Set<BuildingId> residentOf;
    private final Set<BuildingId> administratorOf;

    private BuildingAccess(UserId userId, Set<BuildingId> residentOf, Set<BuildingId> administratorOf) {
        this.userId = userId;
        this.residentOf = Set.copyOf(residentOf);
        this.administratorOf = Set.copyOf(administratorOf);
    }

    /**
     * Builds the access of {@code userId} from its memberships. Memberships of
     * other users, revoked memberships and memberships outside their validity
     * window grant nothing.
     */
    public static BuildingAccess of(UserId userId, Collection<Membership> memberships, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(now, "now");
        Set<BuildingId> resident = new HashSet<>();
        Set<BuildingId> administrator = new HashSet<>();
        for (Membership membership : memberships) {
            if (!membership.userId().equals(userId) || !membership.isActiveAt(now)) {
                continue;
            }
            switch (membership.role()) {
                case RESIDENT -> resident.add(membership.buildingId());
                case ADMINISTRATOR -> administrator.add(membership.buildingId());
            }
        }
        return new BuildingAccess(userId, resident, administrator);
    }

    public UserId userId() {
        return userId;
    }

    public boolean isMemberOf(BuildingId building) {
        return residentOf.contains(building) || administratorOf.contains(building);
    }

    public boolean administers(BuildingId building) {
        return administratorOf.contains(building);
    }

    public Set<BuildingId> administeredBuildings() {
        return administratorOf;
    }

    /**
     * The source a report gets when this user submits it in {@code building},
     * or empty when the user may not report there. A user who is both resident
     * and administrator reports as a resident.
     */
    public Optional<ReportSource> reportSourceFor(BuildingId building) {
        if (residentOf.contains(building)) {
            return Optional.of(ReportSource.RESIDENT);
        }
        if (administratorOf.contains(building)) {
            return Optional.of(ReportSource.ADMINISTRATOR_ON_BEHALF);
        }
        return Optional.empty();
    }

    /** Raw text: own reports, or any report in a building this user administers. */
    public boolean canReadReport(UserId author, BuildingId building) {
        return userId.equals(author) || administers(building);
    }
}
