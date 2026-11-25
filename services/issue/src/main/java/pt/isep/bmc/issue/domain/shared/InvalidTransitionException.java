package pt.isep.bmc.issue.domain.shared;

/**
 * The operation is not allowed from the resource's current state
 * ({@code 409 INVALID_TRANSITION}, technical design 3.3). Nothing was changed.
 */
public final class InvalidTransitionException extends DomainException {

    private final String currentState;

    public InvalidTransitionException(String currentState) {
        super("INVALID_TRANSITION", "The operation is not allowed in the current state.");
        this.currentState = currentState;
    }

    public String currentState() {
        return currentState;
    }
}
