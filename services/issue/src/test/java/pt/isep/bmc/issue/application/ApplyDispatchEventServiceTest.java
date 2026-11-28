package pt.isep.bmc.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.command.ApplyDispatchEventCommand;
import pt.isep.bmc.issue.application.command.ApplyDispatchEventService;
import pt.isep.bmc.issue.application.command.DispatchEventResult;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.AreaKind;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.dispatch.DispatchEvent;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventRecord;
import pt.isep.bmc.issue.domain.dispatch.DispatchEventType;
import pt.isep.bmc.issue.domain.dispatch.InterventionState;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.issue.CriticalCheckStatus;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueState;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;

/** US-07-03 at the application layer: one transaction per event, under the issue's lock, never twice. */
class ApplyDispatchEventServiceTest {

    static final Instant NOW = Instant.parse("2026-11-28T10:00:30.123456Z");
    static final Instant NOW_MILLIS = Instant.parse("2026-11-28T10:00:30.123Z");
    static final BuildingId A = new BuildingId(UUID.randomUUID());
    static final Area GARAGE = new Area(new AreaId(UUID.randomUUID()), A, "Garage level -1", AreaKind.COMMON_AREA);
    static final UUID INTERVENTION = UUID.randomUUID();
    static final String CORRELATION = "corr-dispatch-1";

    InMemoryPorts ports;
    ApplyDispatchEventService service;
    Issue issue;

    @BeforeEach
    void setUp() {
        ports = new InMemoryPorts();
        ports.areas.put(GARAGE.id(), GARAGE);
        issue = Issue.restore(new IssueId(UUID.randomUUID()), A, GARAGE.id(), IssueState.TRIAGED, 2,
                IssueCategory.PLUMBING, IssueUrgency.HIGH, CriticalCheckStatus.COMPLETED, "critical-alerts-v1", null,
                NOW.minusSeconds(600), NOW.minusSeconds(300));
        ports.issues.add(issue);
        service = new ApplyDispatchEventService(ports, ports, ports, ports, ports, ports, ports,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    ApplyDispatchEventCommand command(String eventId, IssueId issueId, long version, InterventionState state) {
        return new ApplyDispatchEventCommand(new DispatchEvent(eventId, DispatchEventType.INTERVENTION_APPROVED,
                INTERVENTION, version, A, issueId, state, NOW.minusSeconds(5), Optional.empty(), Optional.empty(),
                false, Optional.of("contractor-1")), CORRELATION);
    }

    Issue stored() {
        return ports.findForUpdate(issue.id()).orElseThrow();
    }

    @Test
    void anAppliedEventWritesTheIssueTheInterventionTheHistoryTheRecordAndIssueStateChanged() {
        DispatchEventResult result = service.apply(command("e-1", issue.id(), 2, InterventionState.SENT));

        assertThat(result).isEqualTo(DispatchEventResult.APPLIED);
        assertThat(stored().state()).isEqualTo(IssueState.IN_COORDINATION);
        assertThat(stored().version()).isEqualTo(3);
        assertThat(stored().updatedAt()).isEqualTo(NOW_MILLIS);
        assertThat(ports.interventions.get(INTERVENTION).state()).isEqualTo(InterventionState.SENT);
        assertThat(ports.history).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(HistoryEntryType.INTERVENTION_APPROVED);
            assertThat(entry.sequence()).isEqualTo(1);
        });
        assertThat(ports.dispatchEvents.get("e-1")).satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(DispatchEventRecord.Outcome.APPLIED);
            assertThat(record.historySequence()).contains(1);
            assertThat(record.receivedAt()).isEqualTo(NOW_MILLIS);
        });
        assertThat(ports.outbox).singleElement().satisfies(row -> {
            assertThat(row.event()).isInstanceOf(IssueStateChanged.class);
            assertThat(row.correlationId()).isEqualTo(CORRELATION);
        });
    }

    @Test
    void aRepeatedDeliveryChangesNothing() {
        service.apply(command("e-1", issue.id(), 2, InterventionState.SENT));

        DispatchEventResult again = service.apply(command("e-1", issue.id(), 2, InterventionState.SENT));

        assertThat(again).isEqualTo(DispatchEventResult.DUPLICATE);
        assertThat(stored().version()).isEqualTo(3);
        assertThat(ports.history).hasSize(1);
        assertThat(ports.outbox).hasSize(1);
        assertThat(ports.dispatchEvents).hasSize(1);
    }

    @Test
    void aLateEventIsRecordedAndShownButMovesNothing() {
        service.apply(command("e-2", issue.id(), 4, InterventionState.BOOKED));

        DispatchEventResult late = service.apply(command("e-1", issue.id(), 2, InterventionState.SENT));

        assertThat(late).isEqualTo(DispatchEventResult.HISTORY_ONLY);
        assertThat(stored().state()).isEqualTo(IssueState.SCHEDULED);
        assertThat(ports.history).hasSize(2);
        assertThat(ports.outbox).hasSize(1);
        assertThat(ports.interventions.get(INTERVENTION).lastVersion()).isEqualTo(4);
    }

    @Test
    void anEventForAnUnknownIssueIsKeptForInspectionAndCountsAsReceived() {
        IssueId unknown = new IssueId(UUID.randomUUID());

        DispatchEventResult result = service.apply(command("e-1", unknown, 2, InterventionState.SENT));
        DispatchEventResult again = service.apply(command("e-1", unknown, 2, InterventionState.SENT));

        assertThat(result).isEqualTo(DispatchEventResult.FAILED);
        assertThat(again).isEqualTo(DispatchEventResult.DUPLICATE);
        assertThat(ports.dispatchEvents.get("e-1").reason()).contains(DispatchEventRecord.UNKNOWN_ISSUE);
        assertThat(ports.history).isEmpty();
        assertThat(ports.interventions).isEmpty();
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void aFailedEventWritesOnlyItsRecord() {
        ports.issues.set(0, Issue.restore(issue.id(), A, GARAGE.id(), IssueState.RECEIVED, 1, null, null,
                CriticalCheckStatus.COMPLETED, "critical-alerts-v1", null, NOW.minusSeconds(600),
                NOW.minusSeconds(600)));

        DispatchEventResult result = service.apply(command("e-1", issue.id(), 2, InterventionState.SENT));

        assertThat(result).isEqualTo(DispatchEventResult.FAILED);
        assertThat(ports.dispatchEvents.get("e-1").reason()).contains(DispatchEventRecord.NOT_DISPATCHABLE);
        assertThat(stored().state()).isEqualTo(IssueState.RECEIVED);
        assertThat(ports.history).isEmpty();
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void whenAnyWriteFailsNothingIsStoredAndTheSameEventIsAppliedOnTheNextDelivery() {
        ports.failAppendNumber = 1;

        assertThatThrownBy(() -> service.apply(command("e-1", issue.id(), 2, InterventionState.SENT)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(stored().state()).isEqualTo(IssueState.TRIAGED);
        assertThat(stored().version()).isEqualTo(2);
        assertThat(ports.history).isEmpty();
        assertThat(ports.interventions).isEmpty();
        assertThat(ports.dispatchEvents).isEmpty();

        assertThat(service.apply(command("e-1", issue.id(), 2, InterventionState.SENT)))
                .isEqualTo(DispatchEventResult.APPLIED);
        assertThat(stored().state()).isEqualTo(IssueState.IN_COORDINATION);
    }

    @Test
    void historyEntriesFollowTheIssuesOtherEntries() {
        service.apply(command("e-1", issue.id(), 2, InterventionState.SENT));
        service.apply(command("e-2", issue.id(), 3, InterventionState.SCHEDULING_PENDING));

        assertThat(ports.history).extracting(entry -> entry.sequence()).containsExactly(1, 2);
        assertThat(ports.dispatchEvents.get("e-2").historySequence()).contains(2);
    }
}
