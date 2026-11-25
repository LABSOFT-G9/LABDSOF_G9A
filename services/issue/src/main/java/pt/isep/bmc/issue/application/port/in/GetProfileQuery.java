package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.query.CallerIdentity;
import pt.isep.bmc.issue.application.query.Profile;

/** {@code GET /me} (technical design 4.2). */
public interface GetProfileQuery {

    Profile get(CallerIdentity caller);
}
