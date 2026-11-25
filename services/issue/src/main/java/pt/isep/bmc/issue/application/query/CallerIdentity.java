package pt.isep.bmc.issue.application.query;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.issue.domain.membership.PlatformRole;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * What the validated access token says about the caller (ADR-005): who it is,
 * and what the identity provider knows of them. Only {@code userId} is ever
 * used to authorise; the rest is shown back to the caller in {@code GET /me}.
 *
 * @param displayName the account's name at the identity provider, if the token carries one
 * @param email       the account's email address, if the token carries one; never stored here
 */
public record CallerIdentity(
        UserId userId, Optional<String> displayName, Optional<String> email, Set<PlatformRole> platformRoles) {

    public CallerIdentity {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(email, "email");
        platformRoles = Set.copyOf(platformRoles);
    }

    /** A caller of whom only the subject is known. */
    public static CallerIdentity of(UserId userId) {
        return new CallerIdentity(userId, Optional.empty(), Optional.empty(), Set.of());
    }
}
