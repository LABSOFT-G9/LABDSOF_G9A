package pt.isep.bmc.issue.application.query;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * Which issues a caller may list: those the caller reported, every issue of
 * the buildings the caller administers, and the issues with a published
 * summary in the buildings the caller belongs to. {@code building} only
 * narrows.
 *
 * @param reporter the caller; also whose impact and subscription the rows show
 */
public record IssueListCriteria(
        UserId reporter,
        Set<BuildingId> administeredBuildings,
        Set<BuildingId> memberBuildings,
        Optional<BuildingId> building,
        int page,
        int size) {

    public IssueListCriteria {
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(building, "building");
        administeredBuildings = Set.copyOf(administeredBuildings);
        memberBuildings = Set.copyOf(memberBuildings);
    }
}
