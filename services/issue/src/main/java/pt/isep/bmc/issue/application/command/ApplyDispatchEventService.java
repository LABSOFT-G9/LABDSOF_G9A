package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import pt.isep.bmc.issue.application.port.in.ApplyDispatchEventUseCase;
import pt.isep.bmc.issue.application.port.out.BuildingRepository;
import pt.isep.bmc.issue.application.port.out.DispatchEventLog;
import pt.isep.bmc.issue.application.port.out.EventOutbox;
import pt.isep.bmc.issue.application.port.out.IdGenerator;
import pt.isep.bmc.issue.application.port.out.InterventionRepository;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.application.port.out.OutboxEvent;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.dispatch.DispatchEvent;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventIntake;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventRecord;
import pt.isep.bmc.issue.domain.dispatch.DispatchOutcome;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.issue.Issue;

/**
 * Applies a Dispatch event in one transaction, under the issue's lock, so two
 * events of one issue never interleave: the record of the event, the
 * intervention's projection, the history entry, the issue and
 * {@code IssueStateChanged} are written together or not at all.
 */
public final class ApplyDispatchEventService implements ApplyDispatchEventUseCase {

    private final TransactionRunner transactions;
    private final BuildingRepository buildings;
    private final IssueRepository issues;
    private final InterventionRepository interventions;
    private final DispatchEventLog log;
    private final EventOutbox outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public ApplyDispatchEventService(
            TransactionRunner transactions,
            BuildingRepository buildings,
            IssueRepository issues,
            InterventionRepository interventions,
            DispatchEventLog log,
            EventOutbox outbox,
            IdGenerator ids,
            Clock clock) {
        this.transactions = transactions;
        this.buildings = buildings;
        this.issues = issues;
        this.interventions = interventions;
        this.log = log;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public DispatchEventResult apply(ApplyDispatchEventCommand command) {
        DispatchEvent event = command.event();
        // Millisecond precision, so the stored and the returned times are the same.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return transactions.inTransaction(() -> {
            Optional<Issue> found = issues.findForUpdate(event.issueId());
            // Under the issue's lock: a second delivery of the same event waits here, then finds the first.
            if (log.exists(event.eventId())) {
                return DispatchEventResult.DUPLICATE;
            }
            if (found.isEmpty()) {
                log.add(DispatchEventRecord.unknownIssue(event, now));
                return DispatchEventResult.FAILED;
            }
            Issue issue = found.get();
            String areaLabel = buildings.findArea(issue.areaId()).map(Area::label)
                    .orElseThrow(() -> new IllegalStateException("issue points at a missing area"));

            DispatchOutcome outcome = DispatchEventIntake.apply(issue, interventions.forIssue(issue.id()), event,
                    issues.nextHistorySequence(issue.id()), areaLabel, now);
            if (outcome.retryLaterNeeded()) {
                return DispatchEventResult.RETRY_LATER;
            }

            outcome.issue().ifPresent(issues::update);
            outcome.history().ifPresent(issues::appendHistory);
            outcome.intervention().ifPresent(interventions::save);
            log.add(outcome.record());
            for (DomainEvent changed : outcome.events()) {
                outbox.append(new OutboxEvent(ids.newId(), changed, command.correlationId()));
            }
            return switch (outcome.record().outcome()) {
                case APPLIED -> DispatchEventResult.APPLIED;
                case HISTORY_ONLY -> DispatchEventResult.HISTORY_ONLY;
                case FAILED -> DispatchEventResult.FAILED;
            };
        });
    }
}
