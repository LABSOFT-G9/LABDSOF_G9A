package pt.isep.bmc.issue.domain.issue;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.shared.InvalidTransitionException;
import pt.isep.bmc.issue.domain.shared.VersionConflictException;

/**
 * The shared fault an administrator manages (technical design 2.1). One or
 * more reports point at it; {@code buildingId} never changes.
 *
 * <p>Immutable: every change returns the next version of the issue. Category
 * and urgency are empty until an administrator decides them; a suggestion
 * never fills them in (US-02: an undecided value stays pending).
 */
public final class Issue {

    public static final long INITIAL_VERSION = 1;

    private final IssueId id;
    private final BuildingId buildingId;
    private final AreaId areaId;
    private final IssueState state;
    private final long version;
    private final IssueCategory category;
    private final IssueUrgency urgency;
    private final CriticalCheckStatus criticalCheckStatus;
    private final SummaryId publishedSummaryId;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Issue(
            IssueId id,
            BuildingId buildingId,
            AreaId areaId,
            IssueState state,
            long version,
            IssueCategory category,
            IssueUrgency urgency,
            CriticalCheckStatus criticalCheckStatus,
            SummaryId publishedSummaryId,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.buildingId = Objects.requireNonNull(buildingId, "buildingId");
        this.areaId = Objects.requireNonNull(areaId, "areaId");
        this.state = Objects.requireNonNull(state, "state");
        this.version = version;
        this.category = category;
        this.urgency = urgency;
        this.criticalCheckStatus = Objects.requireNonNull(criticalCheckStatus, "criticalCheckStatus");
        this.publishedSummaryId = publishedSummaryId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < INITIAL_VERSION) {
            throw new IllegalArgumentException("version must be at least " + INITIAL_VERSION);
        }
        if ((category == null) != (urgency == null)) {
            throw new IllegalArgumentException("category and urgency are decided together");
        }
    }

    /** A new issue for a new report: {@code received}, version 1, nothing decided, critical check pending. */
    public static Issue receive(IssueId id, BuildingId buildingId, AreaId areaId, Instant now) {
        return new Issue(id, buildingId, areaId, IssueState.RECEIVED, INITIAL_VERSION, null, null,
                CriticalCheckStatus.PENDING, null, now, now);
    }

    public static Issue restore(
            IssueId id,
            BuildingId buildingId,
            AreaId areaId,
            IssueState state,
            long version,
            IssueCategory category,
            IssueUrgency urgency,
            CriticalCheckStatus criticalCheckStatus,
            Instant createdAt,
            Instant updatedAt) {
        return restore(id, buildingId, areaId, state, version, category, urgency, criticalCheckStatus, null,
                createdAt, updatedAt);
    }

    /** @param publishedSummaryId the current published summary, or {@code null} when none was published */
    public static Issue restore(
            IssueId id,
            BuildingId buildingId,
            AreaId areaId,
            IssueState state,
            long version,
            IssueCategory category,
            IssueUrgency urgency,
            CriticalCheckStatus criticalCheckStatus,
            SummaryId publishedSummaryId,
            Instant createdAt,
            Instant updatedAt) {
        return new Issue(id, buildingId, areaId, state, version, category, urgency, criticalCheckStatus,
                publishedSummaryId, createdAt, updatedAt);
    }

    /**
     * An administrator's triage decision (technical design 3.1): from
     * {@code received}, {@code in_triage} or {@code triaged} to
     * {@code triaged}, with the decided category and urgency and the next
     * version. A decision can be corrected by deciding again.
     *
     * @param expectedVersion the version the administrator was looking at ({@code If-Match})
     * @throws VersionConflictException   if the issue has changed since then; checked first
     * @throws InvalidTransitionException if triage is no longer possible in the current state
     */
    public Issue decideTriage(long expectedVersion, IssueCategory category, IssueUrgency urgency, Instant now) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(urgency, "urgency");
        if (expectedVersion != version) {
            throw new VersionConflictException(version, state.value());
        }
        if (!state.allowsManualTriage()) {
            throw new InvalidTransitionException(state.value());
        }
        return new Issue(id, buildingId, areaId, IssueState.TRIAGED, version + 1, category, urgency,
                criticalCheckStatus, publishedSummaryId, createdAt, now);
    }

    /**
     * A triage suggestion arrived (AI, rules or fallback; technical design
     * 3.1 and 3.3). It moves a {@code received} issue to {@code in_triage},
     * and only if it was computed from the issue's current version. It never
     * sets category or urgency: those stay undecided until a person decides.
     *
     * @param sourceVersion the issue version the suggestion was computed from
     * @return the next version of the issue, or empty when the suggestion is
     *         late (the issue has moved on, or a decision was already made)
     *         and must not change anything
     */
    public Optional<Issue> suggestTriage(long sourceVersion, Instant now) {
        if (state != IssueState.RECEIVED || sourceVersion != version) {
            return Optional.empty();
        }
        return Optional.of(new Issue(id, buildingId, areaId, IssueState.IN_TRIAGE, version + 1, category, urgency,
                criticalCheckStatus, publishedSummaryId, createdAt, now));
    }

    /**
     * An administrator published a summary for the building, or replaced the
     * one already published (US-04). The state, category and urgency stay as
     * they are; only the version moves, so Dispatch's copy of the issue picks
     * up the new summary reference. No state forbids it: the design names none.
     *
     * @param expectedVersion the version the administrator was looking at ({@code If-Match})
     * @throws VersionConflictException if the issue has changed since then
     */
    public Issue publishSummary(long expectedVersion, SummaryId summaryId, Instant now) {
        Objects.requireNonNull(summaryId, "summaryId");
        if (expectedVersion != version) {
            throw new VersionConflictException(version, state.value());
        }
        return new Issue(id, buildingId, areaId, state, version + 1, category, urgency, criticalCheckStatus,
                summaryId, createdAt, now);
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

    /** The decided category, or empty while no administrator has decided. */
    public Optional<IssueCategory> category() {
        return Optional.ofNullable(category);
    }

    /** The decided urgency, or empty while no administrator has decided. */
    public Optional<IssueUrgency> urgency() {
        return Optional.ofNullable(urgency);
    }

    public CriticalCheckStatus criticalCheckStatus() {
        return criticalCheckStatus;
    }

    /** The current published summary, or empty while nothing was published. */
    public Optional<SummaryId> publishedSummaryId() {
        return Optional.ofNullable(publishedSummaryId);
    }

    /** Whether the residents of the building may see the issue (US-04). */
    public boolean isPublished() {
        return publishedSummaryId != null;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "Issue[id=" + id + ", state=" + state.value() + ", version=" + version + "]";
    }
}
