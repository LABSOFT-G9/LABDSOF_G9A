package pt.isep.bmc.issue.adapters.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.BuildingRepository;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;

@Repository
class JpaBuildingRepository implements BuildingRepository {

    private final AreaJpaRepository areas;

    JpaBuildingRepository(AreaJpaRepository areas) {
        this.areas = areas;
    }

    @Override
    public Optional<Area> findArea(AreaId id) {
        return areas.findById(id.toString()).map(PersistenceMapper::toDomain);
    }

    @Override
    public List<Area> findAreas(BuildingId buildingId) {
        return areas.findByBuildingIdOrderByLabelAsc(buildingId.toString()).stream()
                .map(PersistenceMapper::toDomain)
                .toList();
    }
}
