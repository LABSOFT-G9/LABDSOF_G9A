package pt.isep.bmc.issue.domain.shared;

/**
 * The command was based on an older version of the resource
 * ({@code 409 VERSION_CONFLICT}, technical design 3.3). Nothing was changed;
 * the caller reloads and decides again.
 */
public final class VersionConflictException extends DomainException {

    private final long currentVersion;
    private final String currentState;

    public VersionConflictException(long currentVersion, String currentState) {
        super("VERSION_CONFLICT", "The resource changed since you loaded it.");
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
