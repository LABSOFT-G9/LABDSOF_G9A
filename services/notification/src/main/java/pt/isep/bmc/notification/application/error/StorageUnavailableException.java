package pt.isep.bmc.notification.application.error;

/**
 * The service's own database could not complete the operation
 * ({@code 503 DEPENDENCY_UNAVAILABLE}). Nothing was committed.
 */
public final class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(Throwable cause) {
        super("The database is unavailable.", cause);
    }
}
