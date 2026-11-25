package pt.isep.bmc.issue.application.query;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * Which issues a caller may list: those the caller reported, plus every issue
 * of the buildings the caller administers. {@code building} only narrows.
 */
public record IssueListCriteria(
        UserId reporter, Set<BuildingId> administeredBuildings, Optional<BuildingId> building, int page, int size) {

    public IssueListCriteria {
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(building, "building");
        administeredBuildings = Set.copyOf(administeredBuildings);
    }
}
