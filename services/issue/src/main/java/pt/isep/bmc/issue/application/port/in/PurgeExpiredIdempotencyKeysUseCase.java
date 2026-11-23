package pt.isep.bmc.issue.application.port.in;

/** Removes idempotency keys older than 24 h (technical design 4.6). */
public interface PurgeExpiredIdempotencyKeysUseCase {

    int purge();
}
