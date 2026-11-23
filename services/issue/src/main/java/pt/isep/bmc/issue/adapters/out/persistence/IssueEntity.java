package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "issues")
class IssueEntity {

    @Id
    @Column(name = "id", length = 36)
    String id;

    @Column(name = "building_id", nullable = false, length = 36)
    String buildingId;

    @Column(name = "area_id", nullable = false, length = 36)
    String areaId;

    @Column(name = "state", nullable = false)
    String state;

    @Column(name = "version", nullable = false)
    long version;

    @Column(name = "critical_check_status", nullable = false)
    String criticalCheckStatus;

    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected IssueEntity() {
    }
}
