package pt.isep.bmc.dispatch.adapters.in.rest;

import org.springframework.security.oauth2.jwt.Jwt;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * The caller is the token's {@code sub} and nothing else (technical design
 * 4.3 step 1): roles, building IDs or issue states sent by the client are
 * never authority. The token was already validated, including a non-blank
 * {@code sub}.
 */
final class Actors {

    private Actors() {
    }

    static UserId of(Jwt jwt) {
        return new UserId(jwt.getSubject());
    }
}
