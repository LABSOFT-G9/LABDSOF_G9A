package pt.isep.bmc.issue.domain.shared;

/**
 * The selected area does not exist or belongs to another building
 * ({@code 400 INVALID_AREA}). Raised only for callers who may report in the
 * selected building, so it reveals nothing about other buildings.
 */
public final class InvalidAreaException extends DomainException {

    public InvalidAreaException() {
        super("INVALID_AREA", "The area does not exist in the selected building.");
    }
}
