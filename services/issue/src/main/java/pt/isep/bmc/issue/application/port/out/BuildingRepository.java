package pt.isep.bmc.issue.application.port.out;

import java.util.List;
import java.util.Optional;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;

public interface BuildingRepository {

    Optional<Area> findArea(AreaId id);

    List<Area> findAreas(BuildingId buildingId);
}
