package pt.isep.bmc.dispatch.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.dispatch.application.port.out.DuplicateProjectionRowException;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.domain.projection.MembershipId;
import pt.isep.bmc.dispatch.domain.projection.ProjectedMembership;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** Plain SQL, so the row lock ({@code FOR UPDATE}) is explicit. Runs on the transaction's connection. */
@Repository
class JdbcMembershipProjectionRepository implements MembershipProjectionRepository {

    private static final String COLUMNS =
            "SELECT membership_id, user_id, building_id, role, active, version, applied_at FROM membership_projection";

    private final JdbcClient jdbc;

    JdbcMembershipProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ProjectedMembership> findByUser(UserId userId) {
        return jdbc.sql(COLUMNS + " WHERE user_id = :user").param("user", userId.value())
                .query(JdbcMembershipProjectionRepository::row).list();
    }

    @Override
    public Optional<ProjectedMembership> findForUpdate(MembershipId id) {
        return jdbc.sql(COLUMNS + " WHERE membership_id = :id FOR UPDATE").param("id", id.toString())
                .query(JdbcMembershipProjectionRepository::row).optional();
    }

    @Override
    public void insert(ProjectedMembership membership) {
        try {
            jdbc.sql("""
                            INSERT INTO membership_projection
                              (membership_id, user_id, building_id, role, active, version, applied_at)
                            VALUES (:id, :user, :building, :role, :active, :version, :applied)
                            """)
                    .params(params(membership))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateProjectionRowException();
        }
    }

    @Override
    public void update(ProjectedMembership membership) {
        jdbc.sql("""
                        UPDATE membership_projection
                           SET user_id = :user, building_id = :building, role = :role, active = :active,
                               version = :version, applied_at = :applied
                         WHERE membership_id = :id
                        """)
                .params(params(membership))
                .update();
    }

    private static Map<String, Object> params(ProjectedMembership membership) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", membership.membershipId().toString());
        params.put("user", membership.userId().value());
        params.put("building", membership.buildingId().toString());
        params.put("role", membership.role());
        params.put("active", membership.active());
        params.put("version", membership.version());
        params.put("applied", Timestamps.toColumn(membership.appliedAt()));
        return params;
    }

    private static ProjectedMembership row(ResultSet rs, int n) throws SQLException {
        return new ProjectedMembership(
                new MembershipId(UUID.fromString(rs.getString("membership_id"))),
                new UserId(rs.getString("user_id")),
                new BuildingId(UUID.fromString(rs.getString("building_id"))),
                rs.getString("role"),
                rs.getBoolean("active"),
                rs.getLong("version"),
                Timestamps.fromColumn(rs.getObject("applied_at", LocalDateTime.class)));
    }
}
