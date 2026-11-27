package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.domain.projection.ProjectedMembership;

/**
 * Applies Issue's {@code MembershipGranted} (active) and {@code
 * MembershipRevoked} (not active) to Dispatch's membership projection
 * (technical design 4.3), by the membership's version.
 */
public interface ApplyMembershipChangeUseCase {

    /** @return whether the event changed the projection; an older or repeated version changes nothing */
    boolean apply(ProjectedMembership membership);
}
