package pt.isep.bmc.dispatch.domain.projection;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * The buildings a caller administers, according to Dispatch's membership
 * projection (technical design 4.3 step 3). Only an active
 * {@code administrator} membership counts; a missing row is a denial. The
 * token's {@code sub} is the only input taken from the request.
 */
public final class AdministratorAccess {

    private final UserId userId;
    private final Set<BuildingId> administered;

    private AdministratorAccess(UserId userId, Set<BuildingId> administered) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.administered = Set.copyOf(administered);
    }

    /** @param memberships every projected membership of {@code userId}, in any role or status */
    public static AdministratorAccess of(UserId userId, Collection<ProjectedMembership> memberships) {
        return new AdministratorAccess(userId, memberships.stream()
                .filter(m -> m.userId().equals(userId))
                .filter(ProjectedMembership::isActiveAdministrator)
                .map(ProjectedMembership::buildingId)
                .collect(Collectors.toSet()));
    }

    public UserId userId() {
        return userId;
    }

    public boolean administers(BuildingId building) {
        return administered.contains(building);
    }

    public Set<BuildingId> administeredBuildings() {
        return administered;
    }

    /**
     * @throws AccessDeniedException ({@code 404}) unless the caller administers the building:
     *         residents, contractors and other buildings' administrators alike
     */
    public void requireAdministers(BuildingId building) {
        if (!administers(building)) {
            throw new AccessDeniedException();
        }
    }
}
