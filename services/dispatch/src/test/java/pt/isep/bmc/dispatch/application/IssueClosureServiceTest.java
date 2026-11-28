package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.command.BookingWorker;
import pt.isep.bmc.dispatch.application.command.CompletionCommand;
import pt.isep.bmc.dispatch.application.command.ExecutionService;
import pt.isep.bmc.dispatch.application.command.IssueClosure;
import pt.isep.bmc.dispatch.application.command.IssueClosureService;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.event.InterventionCancellationPending;
import pt.isep.bmc.dispatch.domain.event.InterventionCancelled;
import pt.isep.bmc.dispatch.domain.intervention.CancelReason;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/** US-09, technical design 4.3 step 8: Issue's closure ends the issue's intervention, once. */
class IssueClosureServiceTest {

    AgreementFixtures f = new AgreementFixtures();
    FakeCalendar calendar = new FakeCalendar();
    IssueClosureService service = new IssueClosureService(f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.clock);
    BookingWorker worker = new BookingWorker(f.ports, f.ports, f.ports, calendar, f.ports, f.ports, f.clock);

    IssueClosure closure(IssueClosure.Kind kind) {
        return new IssueClosure(f.issueId, kind, "c-issue");
    }

    @Test
    void aRequestNotYetAgreedIsCancelledWithTheIssuesReason() {
        f.sent();

        assertThat(service.apply(closure(IssueClosure.Kind.CANCELLED))).isEqualTo(1);

        assertThat(f.stored().state()).isEqualTo(InterventionState.CANCELLED);
        assertThat(f.stored().cancelReason()).contains(CancelReason.ISSUE_CANCELLED);
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(InterventionCancelled.class,
                e -> assertThat(e.reasonCode()).isEqualTo(CancelReason.ISSUE_CANCELLED));
        assertThat(service.apply(closure(IssueClosure.Kind.CANCELLED))).as("a repeated event").isZero();
    }

    @Test
    void aManualClosureWhileBookedCancelsTheBookingFirst() {
        f.sent();
        f.responses.respond(f.accept("accept", 2, 1));
        worker.runDue();

        service.apply(closure(IssueClosure.Kind.RESOLVED));

        assertThat(f.stored().state()).isEqualTo(InterventionState.CANCELLATION_PENDING);
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(InterventionCancellationPending.class,
                e -> assertThat(e.reasonCode()).isEqualTo(CancelReason.ISSUE_RESOLVED));
        f.clock.advance(Duration.ofSeconds(1));
        worker.runDue();
        assertThat(f.stored().state()).isEqualTo(InterventionState.CANCELLED);
        assertThat(calendar.count("cancel")).isEqualTo(1);
        assertThat(service.apply(closure(IssueClosure.Kind.RESOLVED))).isZero();
    }

    @Test
    void aReportedCompletionIsClosedWithoutAnEvent() {
        f.sent();
        f.responses.respond(f.accept("accept", 2, 1));
        worker.runDue();
        f.clock.advance(Duration.ofHours(1));
        new ExecutionService(f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.clock)
                .reportCompletion(new CompletionCommand(CONTRACTOR_LOGIN, new IdempotencyKey("done"), f.interventionId,
                        4, "Replaced the cracked pipe joint.", "2026-11-26T10:30:00Z", null, "c"));
        int events = f.ports.outbox.size();

        service.apply(closure(IssueClosure.Kind.RESOLVED));

        assertThat(f.stored().state()).isEqualTo(InterventionState.CLOSED);
        assertThat(f.ports.outbox).hasSize(events);
        assertThat(f.ports.bookings.values()).singleElement()
                .satisfies(b -> assertThat(b.state()).as("the work was done").isEqualTo(BookingState.CONFIRMED));
    }

    @Test
    void anIssueWithoutAnActiveInterventionChangesNothing() {
        f.sent();
        f.responses.respond(f.decline("d", 2, 1, "unavailable", null));

        assertThat(service.apply(closure(IssueClosure.Kind.RESOLVED))).isZero();
        assertThat(f.stored().state()).isEqualTo(InterventionState.DECLINED);
    }
}
