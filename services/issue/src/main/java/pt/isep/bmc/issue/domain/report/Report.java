package pt.isep.bmc.issue.domain.report;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * A resident's (or an administrator's on-behalf) account of a fault
 * (technical design 2.1, J1 steps 1–2). Immutable once submitted; the raw text
 * stays private to the author and the building's administrators.
 */
public final class Report {

    private final ReportId id;
    private final IssueId issueId;
    private final BuildingId buildingId;
    private final AreaId areaId;
    private final UserId authorUserId;
    private final ReportText text;
    private final ReportSource source;
    private final Instant submittedAt;

    private Report(
            ReportId id,
            IssueId issueId,
            BuildingId buildingId,
            AreaId areaId,
            UserId authorUserId,
            ReportText text,
            ReportSource source,
            Instant submittedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.issueId = Objects.requireNonNull(issueId, "issueId");
        this.buildingId = Objects.requireNonNull(buildingId, "buildingId");
        this.areaId = Objects.requireNonNull(areaId, "areaId");
        this.authorUserId = Objects.requireNonNull(authorUserId, "authorUserId");
        this.text = Objects.requireNonNull(text, "text");
        this.source = Objects.requireNonNull(source, "source");
        this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
    }

    /** Rebuilds a stored report; no rule is re-checked. */
    public static Report restore(
            ReportId id,
            IssueId issueId,
            BuildingId buildingId,
            AreaId areaId,
            UserId authorUserId,
            ReportText text,
            ReportSource source,
            Instant submittedAt) {
        return new Report(id, issueId, buildingId, areaId, authorUserId, text, source, submittedAt);
    }

    static Report submit(
            ReportId id,
            IssueId issueId,
            BuildingId buildingId,
            AreaId areaId,
            UserId authorUserId,
            ReportText text,
            ReportSource source,
            Instant submittedAt) {
        return new Report(id, issueId, buildingId, areaId, authorUserId, text, source, submittedAt);
    }

    public ReportId id() {
        return id;
    }

    public IssueId issueId() {
        return issueId;
    }

    public BuildingId buildingId() {
        return buildingId;
    }

    public AreaId areaId() {
        return areaId;
    }

    public UserId authorUserId() {
        return authorUserId;
    }

    public ReportText text() {
        return text;
    }

    public ReportSource source() {
        return source;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    @Override
    public String toString() {
        return "Report[id=" + id + ", issueId=" + issueId + ", buildingId=" + buildingId + ", source=" + source + "]";
    }
}
