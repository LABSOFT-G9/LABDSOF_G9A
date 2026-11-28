package pt.isep.bmc.dispatch.application.port.out;

import java.util.Objects;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/** Keys are scoped per actor and operation (technical design 4.1). */
public record IdempotencyScope(UserId actor, String operation, IdempotencyKey key) {

    public IdempotencyScope {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(key, "key");
    }
}
