package pt.isep.bmc.issue.domain.shared;

/**
 * The caller may see the resource but may not perform this operation on it
 * ({@code 403 FORBIDDEN}, technical design 3.3 "wrong actor"). Used only when
 * answering {@code 403} reveals nothing the caller cannot already read.
 */
public final class ForbiddenException extends DomainException {

    public ForbiddenException() {
        super("FORBIDDEN", "You are not allowed to perform this operation.");
    }
}
