package pt.isep.bmc.dispatch.application.port.out;

import java.util.List;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;

/** Dispatch's contractor catalogue (technical design 2.2); synthetic in Sprint B (US-06). */
public interface ContractorCatalogue {

    Optional<Contractor> find(ContractorId id);

    /**
     * Every contractor that lists the building in its coverage, active or
     * not. The domain decides who is eligible; this only narrows the read.
     */
    List<Contractor> findCovering(BuildingId building);
}
