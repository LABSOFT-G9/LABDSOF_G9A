package pt.isep.bmc.issue.application.port.in;

import java.util.List;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.UserId;

/** {@code GET /buildings/{id}/areas}: members of the building only. */
public interface ListBuildingAreasQuery {

    List<Area> list(UserId actor, BuildingId building);
}
