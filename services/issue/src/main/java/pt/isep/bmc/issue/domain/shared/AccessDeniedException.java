package pt.isep.bmc.issue.domain.shared;

/**
 * The caller has no right over the target building or resource. Answered as
 * {@code 404} so that a caller cannot learn whether something outside their
 * scope exists (security access matrix, section 2).
 */
public final class AccessDeniedException extends DomainException {

    public AccessDeniedException() {
        super("NOT_FOUND", "The resource does not exist or is outside your scope.");
    }
}
