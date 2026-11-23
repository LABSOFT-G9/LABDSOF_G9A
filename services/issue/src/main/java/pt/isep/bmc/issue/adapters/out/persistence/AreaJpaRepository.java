package pt.isep.bmc.issue.adapters.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to area rows; used only by {@link JpaBuildingRepository}. */
interface AreaJpaRepository extends JpaRepository<AreaEntity, String> {

    List<AreaEntity> findByBuildingIdOrderByLabelAsc(String buildingId);
}
