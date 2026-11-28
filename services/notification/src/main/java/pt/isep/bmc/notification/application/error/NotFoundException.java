package pt.isep.bmc.notification.application.error;

/**
 * The resource does not exist or is not the caller's ({@code 404 NOT_FOUND}).
 * The two are not told apart, so an ID never reveals another user's
 * notification.
 */
public final class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("The resource does not exist or is outside your scope.", null, false, false);
    }
}
