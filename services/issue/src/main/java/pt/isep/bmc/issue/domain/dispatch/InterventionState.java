package pt.isep.bmc.issue.domain.dispatch;

import java.util.Optional;
import pt.isep.bmc.issue.domain.issue.IssueState;

/**
 * The state of an intervention as Dispatch reports it in every event
 * ({@code interventionState}, technical design 3.2). Issue never changes it:
 * it only follows it (3.3).
 */
public enum InterventionState {
    AWAITING_APPROVAL("awaiting_approval"),
    SENT("sent"),
    SCHEDULING_PENDING("scheduling_pending"),
    BOOKED("booked"),
    IN_PROGRESS("in_progress"),
    COMPLETION_REPORTED("completion_reported"),
    DECLINED("declined"),
    CANCELLATION_PENDING("cancellation_pending"),
    CANCELLED("cancelled"),
    CLOSED("closed");

    private final String value;

    InterventionState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Declined, cancelled and closed interventions never change again; the issue has no active one. */
    public boolean isTerminal() {
        return this == DECLINED || this == CANCELLED || this == CLOSED;
    }

    /**
     * The issue state this intervention state stands for (technical design
     * 3.3, "Applying Dispatch events"), or empty when the issue keeps its
     * state: a pending cancellation, or a closure, which Issue itself decided.
     */
    public Optional<IssueState> issueState() {
        return switch (this) {
            case AWAITING_APPROVAL, SENT, SCHEDULING_PENDING -> Optional.of(IssueState.IN_COORDINATION);
            case BOOKED -> Optional.of(IssueState.SCHEDULED);
            case IN_PROGRESS -> Optional.of(IssueState.IN_PROGRESS);
            case COMPLETION_REPORTED -> Optional.of(IssueState.AWAITING_VALIDATION);
            case DECLINED, CANCELLED -> Optional.of(IssueState.TRIAGED);
            case CANCELLATION_PENDING, CLOSED -> Optional.empty();
        };
    }

    public static Optional<InterventionState> fromValue(String value) {
        for (InterventionState state : values()) {
            if (state.value.equals(value)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
