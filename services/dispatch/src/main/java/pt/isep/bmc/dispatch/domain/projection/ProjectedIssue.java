package pt.isep.bmc.dispatch.domain.projection;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;

/**
 * Dispatch's copy of an issue, written only from Issue's {@code
 * IssueStateChanged} (technical design 2.2 and 4.3). Issue stays the
 * authority; this copy may lag. It never holds report text.
 *
 * @param state        Issue's state value, for example {@code triaged}; Dispatch does not interpret it
 * @param dispatchable whether Issue allows an intervention to be created now
 * @param category     the administrator's decided category; empty while undecided
 * @param appliedAt    when Dispatch applied the event that wrote this row
 */
public record ProjectedIssue(
        IssueId issueId,
        BuildingId buildingId,
        String state,
        long version,
        boolean dispatchable,
        Optional<String> category,
        Optional<String> urgency,
        String areaLabel,
        Optional<String> publishedSummaryId,
        Instant appliedAt) {

    public ProjectedIssue {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(urgency, "urgency");
        Objects.requireNonNull(areaLabel, "areaLabel");
        Objects.requireNonNull(publishedSummaryId, "publishedSummaryId");
        Objects.requireNonNull(appliedAt, "appliedAt");
        if (version < 1) {
            throw new IllegalArgumentException("issue version must be at least 1");
        }
    }

    /**
     * Events are applied by version (technical design 3.3 and 4.3): only a
     * newer version replaces the stored copy, so a redelivered or late event
     * cannot undo a later one.
     */
    public boolean replaces(ProjectedIssue stored) {
        return version > stored.version;
    }
}
