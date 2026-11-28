package pt.isep.bmc.notification.application.port.out;

/**
 * The database rolled the transaction back because of a concurrent one (a
 * deadlock or a lock wait timeout). Nothing was committed, so running the
 * same work again is safe. Requests answer it like an unavailable database
 * ({@code 503}, retry); the consumer of {@code NotificationRequested} retries it at once.
 */
public final class TransactionConflictException extends RuntimeException {

    public TransactionConflictException(Throwable cause) {
        super("The transaction was rolled back by a concurrent one.", cause);
    }
}
