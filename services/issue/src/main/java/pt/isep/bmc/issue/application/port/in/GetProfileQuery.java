package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.query.Profile;
import pt.isep.bmc.issue.domain.membership.UserId;

/** {@code GET /me} (technical design 4.2). */
public interface GetProfileQuery {

    Profile get(UserId actor);
}
