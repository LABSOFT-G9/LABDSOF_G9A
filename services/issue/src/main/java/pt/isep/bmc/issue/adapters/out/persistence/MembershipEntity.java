package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "memberships")
class MembershipEntity {

    @Id
    @Column(name = "id", length = 36)
    String id;

    @Column(name = "user_id", nullable = false)
    String userId;

    @Column(name = "building_id", nullable = false, length = 36)
    String buildingId;

    @Column(name = "role", nullable = false)
    String role;

    @Column(name = "status", nullable = false)
    String status;

    @Column(name = "valid_from", nullable = false)
    LocalDateTime validFrom;

    @Column(name = "valid_until")
    LocalDateTime validUntil;

    @Column(name = "version", nullable = false)
    long version;

    protected MembershipEntity() {
    }
}
