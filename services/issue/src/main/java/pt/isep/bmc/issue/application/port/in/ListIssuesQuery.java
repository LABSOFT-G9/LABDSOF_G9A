package pt.isep.bmc.issue.application.port.in;

import java.util.Optional;
import pt.isep.bmc.issue.application.query.IssuePage;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.UserId;

/** {@code GET /issues} (technical design 4.2). */
public interface ListIssuesQuery {

    /**
     * Issues of the buildings the caller administers, plus the issues the
     * caller reported, newest first.
     */
    IssuePage list(UserId actor, Optional<BuildingId> building, int page, int size);
}
