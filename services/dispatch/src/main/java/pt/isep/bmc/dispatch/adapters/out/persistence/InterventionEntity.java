package pt.isep.bmc.dispatch.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Row of {@code interventions}: an adapter class, never the domain aggregate (ADR-009). */
@Entity
@Table(name = "interventions")
class InterventionEntity {

    @Id
    @Column(name = "id", length = 36)
    String id;

    @Column(name = "issue_id", nullable = false, length = 36)
    String issueId;

    @Column(name = "building_id", nullable = false, length = 36)
    String buildingId;

    @Column(name = "contractor_id", nullable = false, length = 36)
    String contractorId;

    @Column(name = "state", nullable = false)
    String state;

    @Column(name = "version", nullable = false)
    long version;

    @Column(name = "current_proposal_version", nullable = false)
    int currentProposalVersion;

    @Column(name = "approved_proposal_version")
    Integer approvedProposalVersion;

    @Column(name = "agreed_proposal_version")
    Integer agreedProposalVersion;

    @Column(name = "contractor_contacted", nullable = false)
    boolean contractorContacted;

    @Column(name = "projected_issue_version", nullable = false)
    long projectedIssueVersion;

    @Column(name = "projection_applied_at", nullable = false)
    LocalDateTime projectionAppliedAt;

    @Column(name = "created_by", nullable = false)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected InterventionEntity() {
    }
}
