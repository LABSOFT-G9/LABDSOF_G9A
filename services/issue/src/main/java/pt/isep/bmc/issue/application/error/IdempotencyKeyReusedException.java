package pt.isep.bmc.issue.application.error;

/**
 * The same {@code Idempotency-Key} was sent with a different body
 * ({@code 422 IDEMPOTENCY_KEY_REUSED}, technical design 3.3). The original
 * result is left unchanged.
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("This idempotency key was already used with a different request.", null, false, false);
    }
}
