package pt.isep.bmc.issue.domain.issue;

/**
 * Issue states (technical design 3.1). This slice only creates issues in
 * {@link #RECEIVED}; the transitions are added by US-02-01 (#87).
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

    public static IssueState fromValue(String value) {
        for (IssueState state : values()) {
            if (state.value.equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown issue state: " + value);
    }
}
