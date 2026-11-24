package pt.isep.bmc.issue.application.port.in;

/**
 * Tells other services about memberships that were stored without an event:
 * the synthetic fixtures are inserted by a migration, not by a command, so
 * Dispatch's administrator projection would never learn of them (technical
 * design 4.3).
 */
public interface AnnounceMembershipsUseCase {

    /**
     * Writes one {@code MembershipGranted} or {@code MembershipRevoked} to the
     * outbox for every membership whose current version has none yet. Safe to
     * run again: an announced version is skipped.
     *
     * @return how many events were written
     */
    int announce();
}
