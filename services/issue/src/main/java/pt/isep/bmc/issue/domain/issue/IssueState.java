package pt.isep.bmc.issue.domain.issue;

/**
 * Issue states (technical design 3.1). The transition table there is an
 * allow-list: what is not listed is rejected. The states after
 * {@link #TRIAGED} are projections of Dispatch's intervention, never set by a
 * client.
 */
public enum IssueState {
    RECEIVED("received"),
    IN_TRIAGE("in_triage"),
    TRIAGED("triaged"),
    IN_COORDINATION("in_coordination"),
    SCHEDULED("scheduled"),
    IN_PROGRESS("in_progress"),
    AWAITING_VALIDATION("awaiting_validation"),
    RESOLVED("resolved"),
    CANCELLED("cancelled");

    private final String value;

    IssueState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Manual triage is allowed while no intervention has started (technical design 3.1). */
    public boolean allowsManualTriage() {
        return this == RECEIVED || this == IN_TRIAGE || this == TRIAGED;
    }

    /** Every state except the two final ones. */
    public boolean isOpen() {
        return this != RESOLVED && this != CANCELLED;
    }

    /** Dispatch may create an intervention for the issue (technical design 3.2). */
    public boolean isDispatchable() {
        return this == TRIAGED || this == IN_COORDINATION;
    }

    public static IssueState fromValue(String value) {
        for (IssueState state : values()) {
            if (state.value.equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown issue state: " + value);
    }
}
