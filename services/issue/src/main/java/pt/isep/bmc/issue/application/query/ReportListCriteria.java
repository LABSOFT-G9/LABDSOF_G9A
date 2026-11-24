package pt.isep.bmc.issue.application.query;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * Reports authored by {@code author}, or in one of {@code administeredBuildings};
 * optionally only those of {@code building}. Ordered newest first.
 */
public record ReportListCriteria(
        UserId author, Set<BuildingId> administeredBuildings, Optional<BuildingId> building, int page, int size) {

    public ReportListCriteria {
        Objects.requireNonNull(author, "author");
        administeredBuildings = Set.copyOf(administeredBuildings);
        Objects.requireNonNull(building, "building");
        if (page < 0 || size < 1) {
            throw new IllegalArgumentException("page must be >= 0 and size >= 1");
        }
    }
}
