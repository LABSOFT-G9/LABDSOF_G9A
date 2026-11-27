package pt.isep.bmc.dispatch.domain.shared;

/**
 * Stale {@code If-Match}: the resource changed since the caller read it
 * ({@code 409 VERSION_CONFLICT}, technical design 3.3). Nothing was changed.
 */
public final class VersionConflictException extends DomainException {

    private final long currentVersion;
    private final String currentState;

    public VersionConflictException(long currentVersion, String currentState) {
        super("VERSION_CONFLICT", "The resource has changed since you read it. Reload it and try again.");
        this.currentVersion = currentVersion;
        this.currentState = currentState;
    }

    public long currentVersion() {
        return currentVersion;
    }

    public String currentState() {
        return currentState;
    }
}
