package pt.isep.bmc.messaging;

/**
 * A message that is not a valid BMC event envelope. It can never be processed,
 * so the consumer rejects it to the dead-letter queue without a retry. The
 * message names the defect, never the payload.
 */
public class InvalidEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidEventException(String message) {
        super(message);
    }
}
