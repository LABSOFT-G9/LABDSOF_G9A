package pt.isep.bmc.issue.application.port.out;

import java.util.List;
import pt.isep.bmc.issue.domain.membership.Membership;
import pt.isep.bmc.issue.domain.membership.UserId;

public interface MembershipRepository {

    /** Every membership of the user, active or not; the domain decides what counts. */
    List<Membership> findByUser(UserId userId);
}
