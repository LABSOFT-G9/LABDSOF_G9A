package pt.isep.bmc.issue.application.command;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.port.in.RecordTriageSuggestionUseCase;
import pt.isep.bmc.issue.application.port.out.BuildingRepository;
import pt.isep.bmc.issue.application.port.out.EventOutbox;
import pt.isep.bmc.issue.application.port.out.IdGenerator;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.application.port.out.OutboxEvent;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.application.port.out.TriageSuggestionRepository;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.SuggestionIntake;
import pt.isep.bmc.issue.domain.issue.SuggestionOutcome;

/**
 * Stores a triage suggestion, its history entry and, when it is applied, the
 * issue's next version and the {@code IssueStateChanged} outbox row, in
 * <b>one</b> transaction (technical design 3.1 and 4.6). A refused suggestion
 * stores nothing.
 *
 * <p>Two rules keep repeated and concurrent deliveries correct:
 * <ul>
 *   <li>the issue row is locked first, so a suggestion and an administrator's
 *       decision on the same issue run one after the other, and whichever
 *       comes second sees the other's version;</li>
 *   <li>the event ID is looked up under that lock and is unique in storage,
 *       so the same event delivered twice, even at the same time, is recorded
 *       once.</li>
 * </ul>
 */
public final class RecordTriageSuggestionService implements RecordTriageSuggestionUseCase {

    private final TransactionRunner transactions;
    private final BuildingRepository buildings;
    private final IssueRepository issues;
    private final TriageSuggestionRepository suggestions;
    private final EventOutbox outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public RecordTriageSuggestionService(
            TransactionRunner transactions,
            BuildingRepository buildings,
            IssueRepository issues,
            TriageSuggestionRepository suggestions,
            EventOutbox outbox,
            IdGenerator ids,
            Clock clock) {
        this.transactions = transactions;
        this.buildings = buildings;
        this.issues = issues;
        this.suggestions = suggestions;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public SuggestionResult record(RecordTriageSuggestionCommand command) {
        // Millisecond precision, so the stored and the returned times are the same.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return transactions.inTransaction(() -> {
            Issue issue = issues.findForUpdate(command.issueId()).orElseThrow(NotFoundException::new);
            if (suggestions.existsByEventId(command.suggestion().eventId())) {
                return SuggestionResult.DUPLICATE;
            }
            String areaLabel = buildings.findArea(issue.areaId()).map(Area::label)
                    .orElseThrow(() -> new IllegalStateException("issue points at a missing area"));

            SuggestionOutcome outcome = SuggestionIntake.record(
                    issue, command.suggestion(), issues.nextHistorySequence(issue.id()), areaLabel, now);

            outcome.issue().ifPresent(issues::update);
            issues.appendHistory(outcome.history());
            suggestions.add(outcome.suggestion());
            for (DomainEvent event : outcome.events()) {
                outbox.append(new OutboxEvent(ids.newId(), event, command.correlationId()));
            }
            return outcome.issue().isPresent() ? SuggestionResult.APPLIED : SuggestionResult.RECORDED_LATE;
        });
    }
}
