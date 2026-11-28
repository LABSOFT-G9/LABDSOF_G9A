package pt.isep.bmc.issue.application.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;

/**
 * Issue's own critical check on an issue, for the building's administrators
 * only. {@code pending} with no signals means "not checked", never "nothing
 * found".
 *
 * @param rulesVersion the rule set that completed the check; empty while it is pending
 * @param signals      one per rule that matched, oldest first; never removed
 */
public record CriticalCheckView(CriticalCheckStatus status, Optional<String> rulesVersion, List<Signal> signals) {

    public CriticalCheckView {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rulesVersion, "rulesVersion");
        signals = List.copyOf(signals);
    }

    public CriticalCheckView withSignals(List<Signal> signals) {
        return new CriticalCheckView(status, rulesVersion, signals);
    }

    /** @param code the rule's stable code; the words for it are the client's */
    public record Signal(String code, Instant raisedAt) {

        public Signal {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(raisedAt, "raisedAt");
        }
    }
}
