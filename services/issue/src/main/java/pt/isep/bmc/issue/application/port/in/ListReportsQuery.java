package pt.isep.bmc.issue.application.port.in;

import java.util.Optional;
import pt.isep.bmc.issue.application.query.ReportPage;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.UserId;

/** {@code GET /reports}: own reports, plus every report of administered buildings. */
public interface ListReportsQuery {

    ReportPage list(UserId actor, Optional<BuildingId> building, int page, int size);
}
