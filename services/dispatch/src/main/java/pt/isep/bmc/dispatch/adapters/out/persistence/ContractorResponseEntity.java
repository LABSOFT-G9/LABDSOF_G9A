package pt.isep.bmc.dispatch.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/** Row of {@code contractor_responses}; written once, never changed. */
@Entity
@Table(name = "contractor_responses")
@IdClass(ContractorResponseEntity.Key.class)
class ContractorResponseEntity {

    @Id
    @Column(name = "intervention_id", length = 36)
    String interventionId;

    @Id
    @Column(name = "proposal_version")
    int proposalVersion;

    @Column(name = "decision", nullable = false)
    String decision;

    @Column(name = "reason_code")
    String reasonCode;

    @Column(name = "note", length = 500)
    String note;

    @Column(name = "counter_proposal_version")
    Integer counterProposalVersion;

    @Column(name = "intervention_version", nullable = false)
    long interventionVersion;

    @Column(name = "responded_by", nullable = false)
    String respondedBy;

    @Column(name = "responded_at", nullable = false)
    LocalDateTime respondedAt;

    protected ContractorResponseEntity() {
    }

    static final class Key implements Serializable {

        String interventionId;
        int proposalVersion;

        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.proposalVersion == proposalVersion
                    && Objects.equals(key.interventionId, interventionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(interventionId, proposalVersion);
        }
    }
}
