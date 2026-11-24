package pt.isep.bmc.issue.domain.building;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a building, the authorisation scope (technical design 2.1). */
public record BuildingId(UUID value) {

    public BuildingId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
