package pt.isep.bmc.dispatch.application.error;

/** Unknown, or outside the caller's scope: both answer {@code 404 NOT_FOUND}. */
public final class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("The resource does not exist or is outside your scope.", null, false, false);
    }
}
