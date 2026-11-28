package pt.isep.bmc.notification.adapters.in.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.isep.bmc.notification.application.command.Receipt;
import pt.isep.bmc.notification.application.error.StorageUnavailableException;
import pt.isep.bmc.notification.application.port.in.ReceiveNotificationRequestUseCase;
import pt.isep.bmc.notification.application.port.out.TransactionConflictException;
import pt.isep.bmc.notification.domain.notification.NotificationRequest;
import pt.isep.bmc.notification.domain.shared.ValidationException;
import tools.jackson.databind.JsonNode;

/**
 * Handles one delivery of {@code NotificationRequested} from
 * {@code notification.inbox} (ADR-007, US-10-01).
 *
 * <ul>
 *   <li>An invalid request is refused at once: retrying cannot fix it. The
 *       log names the event, its correlation ID and the invalid fields with
 *       their reasons, never a value.</li>
 *   <li>A temporary failure (the database unavailable, or a transaction
 *       rolled back by a concurrent one) is tried again, at most
 *       {@code maxAttempts} times in all, with {@code retryDelay} between
 *       attempts. When they are used up the outcome is
 *       {@link Outcome#EXHAUSTED} and the log says so with the same IDs.</li>
 *   <li>Any other failure is a defect that a retry cannot fix: it is
 *       {@link Outcome#EXHAUSTED} at once, logged at {@code ERROR}.</li>
 *   <li>A repeated delivery creates nothing ({@link Outcome#DUPLICATE}).</li>
 * </ul>
 *
 * <p>Counters: {@code bmc.notification.requests} with tag {@code outcome}, and
 * {@code bmc.notification.delivered}. Payloads and exception messages are never
 * logged.
 *
 * <p>The RabbitMQ listener that calls this arrives with the messaging starter
 * (EN-02-02, #78): it acknowledges {@code DELIVERED} and {@code DUPLICATE}, and
 * rejects {@code REJECTED} and {@code EXHAUSTED} to the dead-letter queue. Until
 * then the integration tests feed synthetic events through this class. A
 * failure here never reaches Issue or Dispatch: they only wrote an outbox row
 * in their own committed transaction.
 */
public class NotificationRequestedHandler {

    /** What happened to one delivery. */
    public enum Outcome { DELIVERED, DUPLICATE, REJECTED, EXHAUSTED }

    private static final Logger LOG = LoggerFactory.getLogger(NotificationRequestedHandler.class);

    private final ReceiveNotificationRequestUseCase receive;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final MeterRegistry meters;
    private final Counter delivered;

    public NotificationRequestedHandler(
            ReceiveNotificationRequestUseCase receive, MeterRegistry meters, int maxAttempts, Duration retryDelay) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.receive = receive;
        this.meters = meters;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
        this.delivered = Counter.builder("bmc.notification.delivered")
                .description("Notifications delivered, counted per recipient and channel")
                .register(meters);
    }

    public Outcome handle(EventEnvelope envelope, JsonNode data) {
        NotificationRequest request;
        try {
            request = NotificationRequestedEvents.parse(data);
        } catch (ValidationException invalid) {
            LOG.warn("NotificationRequested {} (correlation {}) refused, not retried: invalid fields [{}]",
                    envelope.eventId(), envelope.correlationId(), invalid.violations().stream()
                            .map(v -> v.field() + ": " + v.reason())
                            .collect(Collectors.joining(", ")));
            return count(Outcome.REJECTED);
        }

        for (int attempt = 1; ; attempt++) {
            try {
                Receipt receipt = receive.receive(request);
                delivered.increment(receipt.delivered());
                if (receipt.isDuplicate()) {
                    LOG.debug("NotificationRequested {} (correlation {}) was delivered again and is ignored",
                            envelope.eventId(), envelope.correlationId());
                    return count(Outcome.DUPLICATE);
                }
                LOG.info("NotificationRequested {} (correlation {}): notification {} made {} deliveries",
                        envelope.eventId(), envelope.correlationId(), request.notificationId(), receipt.delivered());
                return count(Outcome.DELIVERED);
            } catch (RuntimeException failure) {
                if (!isTemporary(failure)) {
                    LOG.error("NotificationRequested {} (correlation {}) failed with {}, which a retry cannot fix; "
                                    + "not retried.",
                            envelope.eventId(), envelope.correlationId(), failure.getClass().getName());
                    return count(Outcome.EXHAUSTED);
                }
                if (attempt >= maxAttempts) {
                    LOG.error("NotificationRequested {} (correlation {}) not stored after {} attempts; "
                                    + "last failure {}. Retries exhausted.",
                            envelope.eventId(), envelope.correlationId(), attempt, failure.getClass().getName());
                    return count(Outcome.EXHAUSTED);
                }
                LOG.info("NotificationRequested {} (correlation {}) attempt {} of {} failed: {}",
                        envelope.eventId(), envelope.correlationId(), attempt, maxAttempts,
                        failure.getClass().getName());
                pause();
            }
        }
    }

    /** Failures where nothing was committed and the same work may succeed later. */
    static boolean isTemporary(RuntimeException failure) {
        return failure instanceof StorageUnavailableException || failure instanceof TransactionConflictException;
    }

    private Outcome count(Outcome outcome) {
        Counter.builder("bmc.notification.requests")
                .description("NotificationRequested deliveries by outcome")
                .tag("outcome", outcome.name().toLowerCase())
                .register(meters)
                .increment();
        return outcome;
    }

    private void pause() {
        if (retryDelay.isZero() || retryDelay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
