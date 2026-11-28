package pt.isep.bmc.dispatch.domain.intervention;

import java.util.Objects;
import java.util.UUID;

/** An intervention, identified by Dispatch. */
public record InterventionId(UUID value) {

    public InterventionId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
