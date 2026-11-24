package pt.isep.bmc.issue.adapters.in.rest;

import org.springframework.security.oauth2.jwt.Jwt;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * The caller is the token's {@code sub} and nothing else: building roles and
 * IDs sent by the client are never authority (ADR-005). The token was already
 * validated, including a non-blank {@code sub}.
 */
final class Actors {

    private Actors() {
    }

    static UserId of(Jwt jwt) {
        return new UserId(jwt.getSubject());
    }
}
