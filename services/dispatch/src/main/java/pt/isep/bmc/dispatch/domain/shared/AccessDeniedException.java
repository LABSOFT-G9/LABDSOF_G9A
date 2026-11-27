package pt.isep.bmc.dispatch.domain.shared;

/**
 * The resource is unknown or outside the caller's scope ({@code 404
 * NOT_FOUND}, technical design 4.3 step 6). Both answer the same, so an
 * administrator of another building never learns whether it exists.
 */
public final class AccessDeniedException extends DomainException {

    public AccessDeniedException() {
        super("NOT_FOUND", "The resource does not exist or is outside your scope.");
    }
}
