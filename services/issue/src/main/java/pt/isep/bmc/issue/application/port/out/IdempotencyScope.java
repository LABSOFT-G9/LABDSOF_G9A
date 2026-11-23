package pt.isep.bmc.issue.application.port.out;

import java.util.Objects;
import pt.isep.bmc.issue.domain.membership.UserId;

/** Keys are scoped per actor and operation (technical design 4.1). */
public record IdempotencyScope(UserId actor, String operation, IdempotencyKey key) {

    public IdempotencyScope {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(key, "key");
    }
}
