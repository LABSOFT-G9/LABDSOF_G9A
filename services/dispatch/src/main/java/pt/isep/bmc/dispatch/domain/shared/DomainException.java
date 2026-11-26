package pt.isep.bmc.dispatch.domain.shared;

/**
 * A business rule was broken. Each subtype carries a stable error code that
 * the REST adapter maps to an HTTP status and an RFC 9457 {@code code} member
 * (technical design section 4.1).
 *
 * <p>Messages never contain user-supplied text, so they are safe to log and to
 * return.
 */
public abstract sealed class DomainException extends RuntimeException
        permits AccessDeniedException, InvalidTransitionException, ValidationException, VersionConflictException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
