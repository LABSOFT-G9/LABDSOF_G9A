package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "reports")
class ReportEntity {

    @Id
    @Column(name = "id", length = 36)
    String id;

    @Column(name = "issue_id", nullable = false, length = 36)
    String issueId;

    @Column(name = "building_id", nullable = false, length = 36)
    String buildingId;

    @Column(name = "area_id", nullable = false, length = 36)
    String areaId;

    @Column(name = "author_user_id", nullable = false)
    String authorUserId;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    String text;

    @Column(name = "source", nullable = false)
    String source;

    @Column(name = "submitted_at", nullable = false)
    LocalDateTime submittedAt;

    protected ReportEntity() {
    }

    @Override
    public String toString() {
        return "ReportEntity[id=" + id + "]";
    }
}
