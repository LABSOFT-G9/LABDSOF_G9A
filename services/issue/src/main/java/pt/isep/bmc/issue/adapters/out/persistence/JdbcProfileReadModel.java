package pt.isep.bmc.issue.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.ProfileReadModel;
import pt.isep.bmc.issue.application.query.MembershipView;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.MembershipId;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.UserId;

@Repository
class JdbcProfileReadModel implements ProfileReadModel {

    private final JdbcClient jdbc;

    JdbcProfileReadModel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findDisplayName(UserId userId) {
        return jdbc.sql("SELECT display_name FROM users WHERE id = :id")
                .param("id", userId.value())
                .query(String.class)
                .optional();
    }

    @Override
    public List<MembershipView> findActiveMemberships(UserId userId, Instant now) {
        return jdbc.sql("""
                        SELECT m.id, m.building_id, b.name, m.role
                          FROM memberships m
                          JOIN buildings b ON b.id = m.building_id
                         WHERE m.user_id = :user AND m.status = 'active'
                           AND m.valid_from <= :now AND (m.valid_until IS NULL OR m.valid_until > :now)
                         ORDER BY b.name, m.role
                        """)
                .param("user", userId.value())
                .param("now", Timestamps.toColumn(now))
                .query((rs, n) -> new MembershipView(
                        new MembershipId(UUID.fromString(rs.getString("id"))),
                        new BuildingId(UUID.fromString(rs.getString("building_id"))),
                        rs.getString("name"),
                        MembershipRole.fromValue(rs.getString("role"))))
                .list();
    }
}
