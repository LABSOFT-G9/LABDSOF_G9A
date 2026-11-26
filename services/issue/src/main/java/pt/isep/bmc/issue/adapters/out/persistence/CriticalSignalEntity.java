package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/** A row of {@code issue_critical_signals}. Inserted once and never changed, so no column is updatable. */
@Entity
@Table(name = "issue_critical_signals")
@IdClass(CriticalSignalEntity.Key.class)
class CriticalSignalEntity {

    @Id
    @Column(name = "issue_id", length = 36, updatable = false)
    String issueId;

    @Id
    @Column(name = "code", length = 40, updatable = false)
    String code;

    @Column(name = "rules_version", nullable = false, updatable = false)
    String rulesVersion;

    @Column(name = "history_sequence", nullable = false, updatable = false)
    int historySequence;

    @Column(name = "raised_at", nullable = false, updatable = false)
    LocalDateTime raisedAt;

    protected CriticalSignalEntity() {
    }

    static final class Key implements Serializable {

        String issueId;
        String code;

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(key.issueId, issueId) && Objects.equals(key.code, code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(issueId, code);
        }
    }
}
