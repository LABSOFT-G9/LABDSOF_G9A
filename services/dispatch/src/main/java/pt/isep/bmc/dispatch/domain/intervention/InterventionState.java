package pt.isep.bmc.dispatch.domain.intervention;

/**
 * Intervention states (technical design 3.2). US-06 creates
 * {@code awaiting_approval} only; the later stories add the transitions.
 * {@code declined}, {@code cancelled} and {@code closed} are terminal: an
 * issue has at most one intervention in any other state.
 */
public enum InterventionState {
    AWAITING_APPROVAL("awaiting_approval"),
    SENT("sent"),
    SCHEDULING_PENDING("scheduling_pending"),
    BOOKED("booked"),
    IN_PROGRESS("in_progress"),
    COMPLETION_REPORTED("completion_reported"),
    CANCELLATION_PENDING("cancellation_pending"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    CLOSED("closed");

    private final String value;

    InterventionState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isTerminal() {
        return this == DECLINED || this == CANCELLED || this == CLOSED;
    }

    public static InterventionState fromValue(String value) {
        for (InterventionState state : values()) {
            if (state.value.equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown intervention state: " + value);
    }
}
