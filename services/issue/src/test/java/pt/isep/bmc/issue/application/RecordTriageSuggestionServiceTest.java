package pt.isep.bmc.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.command.RecordTriageSuggestionCommand;
import pt.isep.bmc.issue.application.command.RecordTriageSuggestionService;
import pt.isep.bmc.issue.application.command.SuggestionResult;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.AreaKind;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.issue.CriticalFindings;
import pt.isep.bmc.issue.domain.issue.CriticalAlertsV1;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.SuggestionInput;
import pt.isep.bmc.issue.domain.report.ReportSource;
import pt.isep.bmc.issue.domain.shared.ValidationException;

/** US-03-03: what one recorded suggestion writes, and what a repeated, late or refused one does not. */
class RecordTriageSuggestionServiceTest {

    static final Instant NOW = Instant.parse("2026-11-24T10:00:30.123456Z");
    static final Instant NOW_MILLIS = Instant.parse("2026-11-24T10:00:30.123Z");
    static final BuildingId A = new BuildingId(UUID.randomUUID());
    static final Area GARAGE = new Area(new AreaId(UUID.randomUUID()), A, "Garage level -1", AreaKind.COMMON_AREA);
    static final String CORRELATION = "corr-triage-1";

    InMemoryPorts ports;
    RecordTriageSuggestionService service;
    Issue issue;

    @BeforeEach
    void setUp() {
        ports = new InMemoryPorts();
        ports.areas.put(GARAGE.id(), GARAGE);
        issue = Issue.receive(new IssueId(UUID.randomUUID()), A, GARAGE.id(), NOW.minusSeconds(30), CriticalFindings.none(CriticalAlertsV1.VERSION));
        ports.issues.add(issue);
        ports.history.add(IssueHistoryEntry.reportReceived(issue.id(), ReportSource.RESIDENT, issue.createdAt()));
        service = new RecordTriageSuggestionService(ports, ports, ports, ports, ports, ports,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static SuggestionInput suggestion(UUID eventId, long sourceVersion, String category) {
        return new SuggestionInput(eventId, sourceVersion, category, "high", "The report mentions a leak.", false,
                "rules", null, null, "rules-v1", null, null);
    }

    RecordTriageSuggestionCommand command(UUID eventId, long sourceVersion) {
        return new RecordTriageSuggestionCommand(issue.id(), suggestion(eventId, sourceVersion, "plumbing"), CORRELATION);
    }

    Issue stored() {
        return ports.findForUpdate(issue.id()).orElseThrow();
    }

    @Test
    void anAppliedSuggestionWritesTheIssueTheEntryTheSuggestionAndTheEventTogether() {
        UUID eventId = UUID.randomUUID();

        assertThat(service.record(command(eventId, 1))).isEqualTo(SuggestionResult.APPLIED);

        assertThat(stored().state()).isEqualTo(IssueState.IN_TRIAGE);
        assertThat(stored().version()).isEqualTo(2);
        assertThat(stored().category()).isEmpty();
        assertThat(stored().updatedAt()).isEqualTo(NOW_MILLIS);
        assertThat(ports.suggestions).singleElement().satisfies(stored -> {
            assertThat(stored.eventId()).isEqualTo(eventId);
            assertThat(stored.applied()).isTrue();
            assertThat(stored.historySequence()).isEqualTo(2);
            assertThat(stored.recordedAt()).isEqualTo(NOW_MILLIS);
        });
        assertThat(ports.history).hasSize(2);
        assertThat(ports.history.get(1).type()).isEqualTo(HistoryEntryType.TRIAGE_SUGGESTED);
        assertThat(ports.history.get(1).issueVersion()).isEqualTo(2);
        assertThat(ports.outbox).singleElement().satisfies(row -> {
            assertThat(row.correlationId()).isEqualTo(CORRELATION);
            assertThat(row.event()).isEqualTo(IssueStateChanged.of(stored(), GARAGE.label()));
        });
    }

    @Test
    void theSameEventAgainWritesNothing() {
        UUID eventId = UUID.randomUUID();
        service.record(command(eventId, 1));

        assertThat(service.record(command(eventId, 1))).isEqualTo(SuggestionResult.DUPLICATE);

        assertThat(stored().version()).isEqualTo(2);
        assertThat(ports.suggestions).hasSize(1);
        assertThat(ports.history).hasSize(2);
        assertThat(ports.outbox).hasSize(1);
    }

    @Test
    void aSecondClassificationOfTheSameRequestIsKeptAsLate() {
        service.record(command(UUID.randomUUID(), 1));

        // The AI service keeps no state: a redelivered request is classified again, as a new event.
        assertThat(service.record(command(UUID.randomUUID(), 1))).isEqualTo(SuggestionResult.RECORDED_LATE);

        assertThat(stored().version()).isEqualTo(2);
        assertThat(ports.suggestions).extracting(s -> s.applied()).containsExactly(true, false);
        assertThat(ports.suggestions).extracting(s -> s.historySequence()).containsExactly(2, 3);
        assertThat(ports.history).hasSize(3);
        assertThat(ports.history.get(2).issueVersion()).isEqualTo(2);
        assertThat(ports.outbox).hasSize(1);
    }

    @Test
    void aSuggestionAfterADecisionIsKeptAndChangesNeitherTheDecisionNorTheVersion() {
        Issue decided = issue.decideTriage(1, IssueCategory.ELECTRICAL, IssueUrgency.CRITICAL, NOW.minusSeconds(5));
        ports.update(decided);

        assertThat(service.record(command(UUID.randomUUID(), 1))).isEqualTo(SuggestionResult.RECORDED_LATE);

        assertThat(stored()).isSameAs(decided);
        assertThat(stored().category()).contains(IssueCategory.ELECTRICAL);
        assertThat(stored().urgency()).contains(IssueUrgency.CRITICAL);
        assertThat(ports.suggestions).singleElement().satisfies(s -> assertThat(s.applied()).isFalse());
        assertThat(ports.history).hasSize(2);
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void aSuggestionThatBreaksTheContractStoresNothing() {
        RecordTriageSuggestionCommand invalid = new RecordTriageSuggestionCommand(
                issue.id(), suggestion(UUID.randomUUID(), 1, "gardening"), CORRELATION);

        assertThatThrownBy(() -> service.record(invalid)).isInstanceOf(ValidationException.class);

        assertThat(stored()).isSameAs(issue);
        assertThat(ports.suggestions).isEmpty();
        assertThat(ports.history).hasSize(1);
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void aSuggestionForAnUnknownIssueIsRefused() {
        RecordTriageSuggestionCommand unknown = new RecordTriageSuggestionCommand(
                new IssueId(UUID.randomUUID()), suggestion(UUID.randomUUID(), 1, "plumbing"), CORRELATION);

        assertThatThrownBy(() -> service.record(unknown)).isInstanceOf(NotFoundException.class);

        assertThat(ports.suggestions).isEmpty();
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void aFailureWhileWritingTheEventLeavesNothingBehind() {
        ports.failAppendNumber = 1;

        assertThatThrownBy(() -> service.record(command(UUID.randomUUID(), 1))).isInstanceOf(RuntimeException.class);

        assertThat(stored()).isSameAs(issue);
        assertThat(ports.suggestions).isEmpty();
        assertThat(ports.history).hasSize(1);
        assertThat(ports.outbox).isEmpty();
    }
}
