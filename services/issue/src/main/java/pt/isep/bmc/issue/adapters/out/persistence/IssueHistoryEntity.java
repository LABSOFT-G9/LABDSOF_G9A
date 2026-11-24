package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "issue_history")
@IdClass(IssueHistoryEntity.Key.class)
class IssueHistoryEntity {

    @Id
    @Column(name = "issue_id", length = 36)
    String issueId;

    @Id
    @Column(name = "sequence")
    int sequence;

    @Column(name = "type", nullable = false)
    String type;

    @Column(name = "visibility", nullable = false)
    String visibility;

    @Column(name = "actor_role", nullable = false)
    String actorRole;

    @Column(name = "issue_version", nullable = false)
    long issueVersion;

    @Column(name = "occurred_at", nullable = false)
    LocalDateTime occurredAt;

    protected IssueHistoryEntity() {
    }

    static final class Key implements Serializable {

        String issueId;
        int sequence;

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.sequence == sequence && Objects.equals(key.issueId, issueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(issueId, sequence);
        }
    }
}
