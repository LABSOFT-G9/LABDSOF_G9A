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
    private final String criticalRulesVersion;
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
            String criticalRulesVersion,
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
        this.criticalRulesVersion = criticalRulesVersion;
        this.publishedSummaryId = publishedSummaryId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < INITIAL_VERSION) {
            throw new IllegalArgumentException("version must be at least " + INITIAL_VERSION);
        }
        if ((category == null) != (urgency == null)) {
            throw new IllegalArgumentException("category and urgency are decided together");
        }
        if ((criticalCheckStatus == CriticalCheckStatus.COMPLETED) != (criticalRulesVersion != null)) {
            throw new IllegalArgumentException("a completed critical check names its rule set, a pending one names none");
        }
    }

    /**
     * A new issue for a new report: {@code received}, version 1, nothing
     * decided. Its critical check is {@code completed} when the rule set ran
     * on the report, and {@code pending} when it did not.
     */
    public static Issue receive(IssueId id, BuildingId buildingId, AreaId areaId, Instant now, CriticalFindings check) {
        Objects.requireNonNull(check, "check");
        return new Issue(id, buildingId, areaId, IssueState.RECEIVED, INITIAL_VERSION, null, null,
                check.completed() ? CriticalCheckStatus.COMPLETED : CriticalCheckStatus.PENDING,
                check.rulesVersion().orElse(null), null, now, now);
    }

    /** An issue with no published summary and no completed critical check, so with no rule set to name. */
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
        return restore(id, buildingId, areaId, state, version, category, urgency, criticalCheckStatus, null, null,
                createdAt, updatedAt);
    }

    /**
     * @param criticalRulesVersion the rule set of a completed critical check, or {@code null} while it is pending
     * @param publishedSummaryId   the current published summary, or {@code null} when none was published
     */
    public static Issue restore(
            IssueId id,
            BuildingId buildingId,
            AreaId areaId,
            IssueState state,
            long version,
            IssueCategory category,
            IssueUrgency urgency,
            CriticalCheckStatus criticalCheckStatus,
            String criticalRulesVersion,
            SummaryId publishedSummaryId,
            Instant createdAt,
            Instant updatedAt) {
        return new Issue(id, buildingId, areaId, state, version, category, urgency, criticalCheckStatus,
                criticalRulesVersion, publishedSummaryId, createdAt, updatedAt);
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
                criticalCheckStatus, criticalRulesVersion, publishedSummaryId, createdAt, now);
    }

    /**
     * The issue version triage was asked for: the one {@code TriageRequested}
     * carries and a suggestion must name to be applied. Triage is asked once,
     * when the issue is received, so this is the first version. A story that
     * asks again (the report an issue is triaged from changes) must store the
     * version of the latest request instead.
     */
    public long triageRequestVersion() {
        return INITIAL_VERSION;
    }

    /**
     * A triage suggestion arrived (AI, rules or fallback; technical design
     * 3.1 and 3.3). It moves a {@code received} issue to {@code in_triage},
     * and only if it answers the triage request ({@link #triageRequestVersion()}).
     * It never sets category or urgency: those stay undecided until a person
     * decides.
     *
     * <p>The suggestion is checked against the request, not against the
     * current version: a summary published in the meantime moves the version
     * but not the report the suggestion was computed from. A decision or an
     * earlier suggestion takes the issue out of {@code received}, and that is
     * what makes a suggestion late.
     *
     * @param sourceVersion the issue version the suggestion says it was computed from
     * @return the next version of the issue, or empty when the suggestion is
     *         late (a person decided, another suggestion was applied, or it
     *         names a version triage was never asked for) and must not change
     *         anything
     */
    public Optional<Issue> suggestTriage(long sourceVersion, Instant now) {
        if (state != IssueState.RECEIVED || sourceVersion != triageRequestVersion()) {
            return Optional.empty();
        }
        return Optional.of(new Issue(id, buildingId, areaId, IssueState.IN_TRIAGE, version + 1, category, urgency,
                criticalCheckStatus, criticalRulesVersion, publishedSummaryId, createdAt, now));
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
                criticalRulesVersion, summaryId, createdAt, now);
    }

    /**
     * The critical check ran on an issue whose check was still pending: the
     * rule set failed when the report arrived, or the issue is older than the
     * check. Nothing an administrator decided changes, in any state.
     *
     * <p>The version moves only when the check raises an alert. A decision
     * made on a page that did not show the alert is then refused as a
     * conflict, so the administrator sees the alert first. A check that finds
     * nothing changes nothing a decision depends on, and the version stays.
     *
     * @param check a check that ran ({@link CriticalFindings#completed()})
     * @param raisesAlert whether the check adds a signal the issue did not have
     * @return the issue with its check completed, or empty when the check was
     *         already completed or {@code check} did not run: nothing to change
     */
    public Optional<Issue> completeCriticalCheck(CriticalFindings check, boolean raisesAlert, Instant now) {
        Objects.requireNonNull(check, "check");
        if (criticalCheckStatus == CriticalCheckStatus.COMPLETED || !check.completed()) {
            return Optional.empty();
        }
        return Optional.of(new Issue(id, buildingId, areaId, state, raisesAlert ? version + 1 : version, category,
                urgency, CriticalCheckStatus.COMPLETED, check.rulesVersion().orElseThrow(), publishedSummaryId,
                createdAt, raisesAlert ? now : updatedAt));
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

    /** The rule set of the completed critical check; empty while the check is pending. */
    public Optional<String> criticalRulesVersion() {
        return Optional.ofNullable(criticalRulesVersion);
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
