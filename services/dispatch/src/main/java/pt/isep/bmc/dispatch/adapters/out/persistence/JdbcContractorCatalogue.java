package pt.isep.bmc.dispatch.adapters.out.persistence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** The catalogue in plain SQL: contractors with their specialisations and coverage. */
@Repository
class JdbcContractorCatalogue implements ContractorCatalogue {

    private final JdbcClient jdbc;

    JdbcContractorCatalogue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Contractor> find(ContractorId id) {
        return load(List.of(id.toString())).stream().findFirst();
    }

    @Override
    public List<Contractor> findCovering(BuildingId building) {
        List<String> ids = jdbc.sql("SELECT contractor_id FROM contractor_coverage WHERE building_id = :building")
                .param("building", building.toString())
                .query(String.class)
                .list();
        return ids.isEmpty() ? List.of() : load(ids);
    }

    private List<Contractor> load(List<String> ids) {
        Map<String, Set<String>> specialisations = new HashMap<>();
        jdbc.sql("SELECT contractor_id, category FROM contractor_specialisations WHERE contractor_id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> specialisations.computeIfAbsent(rs.getString("contractor_id"), k -> new HashSet<>())
                        .add(rs.getString("category")))
                .list();
        Map<String, Set<BuildingId>> coverage = new HashMap<>();
        jdbc.sql("SELECT contractor_id, building_id FROM contractor_coverage WHERE contractor_id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> coverage.computeIfAbsent(rs.getString("contractor_id"), k -> new HashSet<>())
                        .add(new BuildingId(UUID.fromString(rs.getString("building_id")))))
                .list();
        return jdbc.sql("SELECT id, user_id, display_name, active, synthetic FROM contractors WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> {
                    String id = rs.getString("id");
                    return new Contractor(
                            new ContractorId(UUID.fromString(id)),
                            Optional.ofNullable(rs.getString("user_id")).map(UserId::new),
                            rs.getString("display_name"),
                            specialisations.getOrDefault(id, Set.of()),
                            coverage.getOrDefault(id, Set.of()),
                            rs.getBoolean("active"),
                            rs.getBoolean("synthetic"));
                })
                .list();
    }

    @Override
    public Optional<String> findCalendarRef(ContractorId id) {
        return jdbc.sql("SELECT calendar_ref FROM contractors WHERE id = :id")
                .param("id", id.toString())
                .query((rs, n) -> Optional.ofNullable(rs.getString("calendar_ref")))
                .optional()
                .flatMap(ref -> ref)
                .filter(ref -> !ref.isBlank());
    }
}
