package pt.isep.bmc.dispatch.domain.intervention;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why an intervention was cancelled. The code travels in {@code
 * InterventionCancellationPending} and {@code InterventionCancelled}; an
 * administrator's note never does.
 */
public enum CancelReason {
    NO_LONGER_NEEDED("no_longer_needed"),
    CONTRACTOR_UNAVAILABLE("contractor_unavailable"),
    RESCHEDULING("rescheduling"),
    OTHER("other"),
    /** Issue's {@code IssueResolved} (US-09), never given by an administrator. */
    ISSUE_RESOLVED("issue_resolved"),
    /** Issue's {@code IssueCancelled} (US-09), never given by an administrator. */
    ISSUE_CANCELLED("issue_cancelled");

    private final String value;

    CancelReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<CancelReason> fromValue(String value) {
        return Arrays.stream(values()).filter(r -> r.value.equals(value)).findFirst();
    }

    /** The codes an administrator may give; the others come from Issue's events. */
    public static Optional<CancelReason> byAdministrator(String value) {
        return fromValue(value).filter(r -> r != ISSUE_RESOLVED && r != ISSUE_CANCELLED);
    }
}
