package pt.isep.bmc.issue.domain.membership;

import java.util.Arrays;
import java.util.Optional;

/**
 * Coarse, platform-wide roles owned by the identity provider (ADR-005), as
 * opposed to building roles, which are memberships in this service. Shown to
 * the client so it can offer the right screens; it grants nothing here.
 */
public enum PlatformRole {
    /** Project team members who validate contractor registrations (in Dispatch). */
    PLATFORM_STAFF("platform_staff");

    private final String value;

    PlatformRole(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** The role with this wire value, or empty for any role this service does not know. */
    public static Optional<PlatformRole> fromValue(String value) {
        return Arrays.stream(values()).filter(role -> role.value.equals(value)).findFirst();
    }
}
