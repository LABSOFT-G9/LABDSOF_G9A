package pt.isep.bmc.issue.application.port.out;

/**
 * Another transaction already recorded this scope: a concurrent retry. The
 * current transaction must roll back and the stored result be replayed.
 */
public final class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException() {
        super("Idempotency key already recorded", null, false, false);
    }
}
