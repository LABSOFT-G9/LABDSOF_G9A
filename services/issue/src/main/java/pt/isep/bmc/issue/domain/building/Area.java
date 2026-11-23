package pt.isep.bmc.issue.domain.building;

import java.util.Objects;

/** An area of a building that a report can point at (J1 step 1). */
public record Area(AreaId id, BuildingId buildingId, String label, AreaKind kind) {

    public Area {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
    }

    public boolean belongsTo(BuildingId building) {
        return buildingId.equals(building);
    }
}
