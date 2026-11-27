package pt.isep.bmc.dispatch.adapters.in.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.isep.bmc.dispatch.application.port.in.PurgeExpiredIdempotencyKeysUseCase;

/** Deletes idempotency keys past their 24 h retention (technical design 4.6). */
@Component
class IdempotencyKeyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurgeJob.class);

    private final PurgeExpiredIdempotencyKeysUseCase purge;

    IdempotencyKeyPurgeJob(PurgeExpiredIdempotencyKeysUseCase purge) {
        this.purge = purge;
    }

    @Scheduled(initialDelayString = "${bmc.idempotency.purge-interval:PT1H}",
            fixedDelayString = "${bmc.idempotency.purge-interval:PT1H}")
    void run() {
        try {
            int deleted = purge.purge();
            log.info("Purged {} expired idempotency keys", deleted);
        } catch (RuntimeException e) {
            log.warn("Idempotency key purge failed: {}", e.getClass().getName());
        }
    }
}
