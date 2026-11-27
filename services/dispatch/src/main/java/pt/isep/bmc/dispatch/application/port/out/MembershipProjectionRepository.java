package pt.isep.bmc.dispatch.application.port.out;

import java.util.List;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.projection.MembershipId;
import pt.isep.bmc.dispatch.domain.projection.ProjectedMembership;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * Dispatch's copy of Issue's memberships (technical design 2.2 and 4.3).
 * Written only by the {@code MembershipGranted} and {@code MembershipRevoked}
 * consumer, never by a request.
 */
public interface MembershipProjectionRepository {

    /** Every projected membership of the user, in any role or status. */
    List<ProjectedMembership> findByUser(UserId userId);

    /** Locks the row until the transaction ends. */
    Optional<ProjectedMembership> findForUpdate(MembershipId id);

    void insert(ProjectedMembership membership);

    void update(ProjectedMembership membership);
}
