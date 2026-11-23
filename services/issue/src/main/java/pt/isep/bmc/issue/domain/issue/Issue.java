package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;

/**
 * The shared fault an administrator manages (technical design 2.1). One or
 * more reports point at it; {@code buildingId} never changes. This slice
 * creates it; triage, transitions and versioned commands come with US-02-01.
 */
public final class Issue {

    public static final long INITIAL_VERSION = 1;

    private final IssueId id;
    private final BuildingId buildingId;
    private final AreaId areaId;
    private final IssueState state;
    private final long version;
    private final CriticalCheckStatus criticalCheckStatus;
    private final Instant createdAt;

    private Issue(
            IssueId id,
            BuildingId buildingId,
            AreaId areaId,
            IssueState state,
            long version,
            CriticalCheckStatus criticalCheckStatus,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.buildingId = Objects.requireNonNull(buildingId, "buildingId");
        this.areaId = Objects.requireNonNull(areaId, "areaId");
        this.state = Objects.requireNonNull(state, "state");
        this.version = version;
        this.criticalCheckStatus = Objects.requireNonNull(criticalCheckStatus, "criticalCheckStatus");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (version < INITIAL_VERSION) {
            throw new IllegalArgumentException("version must be at least " + INITIAL_VERSION);
        }
    }

    /** A new issue for a new report: {@code received}, version 1, critical check pending. */
    public static Issue receive(IssueId id, BuildingId buildingId, AreaId areaId, Instant now) {
        return new Issue(id, buildingId, areaId, IssueState.RECEIVED, INITIAL_VERSION, CriticalCheckStatus.PENDING, now);
    }

    public static Issue restore(
            IssueId id,
            BuildingId buildingId,
            AreaId areaId,
            IssueState state,
            long version,
            CriticalCheckStatus criticalCheckStatus,
            Instant createdAt) {
        return new Issue(id, buildingId, areaId, state, version, criticalCheckStatus, createdAt);
    }

    public IssueId id() {
        return id;
    }

    public BuildingId buildingId() {
        return buildingId;
    }

    public AreaId areaId() {
        return areaId;
    }

    public IssueState state() {
        return state;
    }

    public long version() {
        return version;
    }

    public CriticalCheckStatus criticalCheckStatus() {
        return criticalCheckStatus;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "Issue[id=" + id + ", state=" + state.value() + ", version=" + version + "]";
    }
}
