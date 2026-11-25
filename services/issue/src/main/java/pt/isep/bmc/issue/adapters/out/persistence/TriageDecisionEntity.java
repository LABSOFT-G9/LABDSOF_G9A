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
@Table(name = "triage_decisions")
@IdClass(TriageDecisionEntity.Key.class)
class TriageDecisionEntity {

    @Id
    @Column(name = "issue_id", length = 36)
    String issueId;

    @Id
    @Column(name = "resulting_version")
    long resultingVersion;

    @Column(name = "category", nullable = false)
    String category;

    @Column(name = "urgency", nullable = false)
    String urgency;

    @Column(name = "reason", nullable = false, length = 500)
    String reason;

    @Column(name = "decided_by", nullable = false)
    String decidedBy;

    @Column(name = "decided_at", nullable = false)
    LocalDateTime decidedAt;

    protected TriageDecisionEntity() {
    }

    static final class Key implements Serializable {

        String issueId;
        long resultingVersion;

        Key() {
        }

        Key(String issueId, long resultingVersion) {
            this.issueId = issueId;
            this.resultingVersion = resultingVersion;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.resultingVersion == resultingVersion
                    && Objects.equals(key.issueId, issueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(issueId, resultingVersion);
        }
    }
}
