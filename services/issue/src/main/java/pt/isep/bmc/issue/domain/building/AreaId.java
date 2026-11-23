package pt.isep.bmc.issue.domain.building;

import java.util.Objects;
import java.util.UUID;

/** Identifier of an area inside a building. */
public record AreaId(UUID value) {

    public AreaId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
