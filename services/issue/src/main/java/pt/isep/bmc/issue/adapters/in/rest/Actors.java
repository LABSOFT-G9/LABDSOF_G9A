package pt.isep.bmc.issue.adapters.in.rest;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import pt.isep.bmc.issue.application.query.CallerIdentity;
import pt.isep.bmc.issue.domain.membership.PlatformRole;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * The caller is the token's {@code sub} and nothing else: building roles and
 * IDs sent by the client are never authority (ADR-005). The token was already
 * validated, including a non-blank {@code sub}.
 */
final class Actors {

    /** Longest name or address passed on from a token; longer values are dropped. */
    private static final int MAX_CLAIM_LENGTH = 255;

    private Actors() {
    }

    static UserId of(Jwt jwt) {
        return new UserId(jwt.getSubject());
    }

    /**
     * The subject plus what Keycloak's token says of the account: {@code name}
     * (else {@code preferred_username}), {@code email} and the realm roles
     * this service knows. For display in {@code GET /me} only.
     */
    static CallerIdentity identity(Jwt jwt) {
        return new CallerIdentity(
                of(jwt),
                text(jwt, "name").or(() -> text(jwt, "preferred_username")),
                text(jwt, "email"),
                platformRoles(jwt));
    }

    private static Optional<String> text(Jwt jwt, String claim) {
        return Optional.ofNullable(jwt.getClaim(claim))
                .filter(String.class::isInstance)
                .map(value -> ((String) value).strip())
                .filter(value -> !value.isEmpty() && value.length() <= MAX_CLAIM_LENGTH);
    }

    /** Keycloak puts realm roles in {@code realm_access.roles}; unknown roles are ignored. */
    private static Set<PlatformRole> platformRoles(Jwt jwt) {
        Set<PlatformRole> roles = EnumSet.noneOf(PlatformRole.class);
        if (jwt.getClaim("realm_access") instanceof Map<?, ?> access && access.get("roles") instanceof Collection<?> names) {
            for (Object name : names) {
                if (name instanceof String value) {
                    PlatformRole.fromValue(value).ifPresent(roles::add);
                }
            }
        }
        return roles;
    }
}
