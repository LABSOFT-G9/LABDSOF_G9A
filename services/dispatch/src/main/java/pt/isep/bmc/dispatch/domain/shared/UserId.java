package pt.isep.bmc.dispatch.domain.shared;

import java.util.Objects;

/**
 * A user, identified by the Keycloak {@code sub} claim (ADR-005). Dispatch
 * takes nothing else from the token; roles and buildings come from its own
 * projections (technical design 4.3).
 */
public record UserId(String value) {

    public static final int MAX_LENGTH = 255;

    public UserId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("user id must be 1 to " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
