package pt.isep.bmc.dispatch.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/** Row of {@code proposal_versions}; written once, never changed. */
@Entity
@Table(name = "proposal_versions")
@IdClass(ProposalVersionEntity.Key.class)
class ProposalVersionEntity {

    @Id
    @Column(name = "intervention_id", length = 36)
    String interventionId;

    @Id
    @Column(name = "number")
    int number;

    @Column(name = "author_role", nullable = false)
    String authorRole;

    @Column(name = "scope_extract", nullable = false, length = 2000)
    String scopeExtract;

    @Column(name = "window_start", nullable = false)
    LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    LocalDateTime windowEnd;

    @Column(name = "terms", nullable = false, length = 1000)
    String terms;

    @Column(name = "created_by", nullable = false)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;

    protected ProposalVersionEntity() {
    }

    static final class Key implements Serializable {

        String interventionId;
        int number;

        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.number == number && Objects.equals(key.interventionId, interventionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(interventionId, number);
        }
    }
}
