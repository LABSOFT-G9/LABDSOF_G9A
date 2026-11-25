package pt.isep.bmc.messaging.inbox;

/**
 * Thrown by an {@link EventHandler} for an event that can never be applied, for
 * example a payload that does not match its schema. The transaction rolls back
 * and the message goes to the dead-letter queue without a retry. The message
 * names the defect, never the payload.
 */
public class RejectEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RejectEventException(String message) {
        super(message);
    }
}
