package pt.isep.bmc.dispatch.domain.shared;

import java.util.Optional;
import java.util.UUID;

/**
 * The command is not allowed from the current state ({@code 409
 * INVALID_TRANSITION}, technical design 3.3). Nothing was changed.
 */
public final class InvalidTransitionException extends DomainException {

    private final String currentState;
    private final UUID activeInterventionId;

    /** @param currentState the state that refused the command (the issue's or the intervention's) */
    public InvalidTransitionException(String currentState) {
        this(currentState, null, "The command is not allowed in the current state.");
    }

    private InvalidTransitionException(String currentState, UUID activeInterventionId, String message) {
        super("INVALID_TRANSITION", message);
        this.currentState = currentState;
        this.activeInterventionId = activeInterventionId;
    }

    /** The issue already has a non-terminal intervention (technical design 3.2, US-06). */
    public static InvalidTransitionException activeIntervention(String issueState, UUID activeInterventionId) {
        return new InvalidTransitionException(issueState, activeInterventionId,
                "The issue already has an active intervention.");
    }

    public String currentState() {
        return currentState;
    }

    /** The intervention that blocks a new one, for the administrator to open instead. */
    public Optional<UUID> activeInterventionId() {
        return Optional.ofNullable(activeInterventionId);
    }
}
