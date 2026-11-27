package pt.isep.bmc.dispatch.domain.shared;

import java.util.Objects;
import java.util.UUID;

/** A building, identified by Issue (the owner of buildings). */
public record BuildingId(UUID value) {

    public BuildingId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
