package pt.isep.bmc.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.dispatch.application.ApplicationFixtures.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.command.BookingWorker;
import pt.isep.bmc.dispatch.application.command.CompletionCommand;
import pt.isep.bmc.dispatch.application.command.ExecutionResult;
import pt.isep.bmc.dispatch.application.command.ExecutionService;
import pt.isep.bmc.dispatch.application.command.ReworkCommand;
import pt.isep.bmc.dispatch.application.command.StartCommand;
import pt.isep.bmc.dispatch.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.domain.event.CompletionReported;
import pt.isep.bmc.dispatch.domain.event.InterventionStarted;
import pt.isep.bmc.dispatch.domain.event.ReworkRequested;
import pt.isep.bmc.dispatch.domain.intervention.CompletionReport;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;
import pt.isep.bmc.dispatch.domain.shared.VersionConflictException;

/** US-09: start, completion by the contractor or for them, and rework. */
class ExecutionServiceTest {

    static final String SUMMARY = "Replaced the cracked pipe joint; light fitting dried and tested.";

    AgreementFixtures f = new AgreementFixtures();
    ExecutionService service;

    @BeforeEach
    void booked() {
        f.sent();
        f.responses.respond(f.accept("accept", 2, 1));
        new BookingWorker(f.ports, f.ports, f.ports, new FakeCalendar(), f.ports, f.ports, f.clock).runDue();
        f.clock.advance(Duration.ofHours(1));
        service = new ExecutionService(f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.ports, f.ports,
                f.clock);
        assertThat(f.stored().state()).isEqualTo(InterventionState.BOOKED);
        assertThat(f.stored().version()).isEqualTo(4);
    }

    StartCommand start(UserId actor, String key, long version) {
        return new StartCommand(actor, new IdempotencyKey(key), f.interventionId, version, "c-start");
    }

    CompletionCommand completion(UserId actor, String key, long version, String reason) {
        return new CompletionCommand(actor, new IdempotencyKey(key), f.interventionId, version, SUMMARY,
                "2026-11-26T10:30:00Z", reason, "c-done");
    }

    ReworkCommand rework(String key, long version) {
        return new ReworkCommand(ADMIN_A, new IdempotencyKey(key), f.interventionId, version,
                "The light still flickers.", "c-rework");
    }

    @Test
    void theContractorStartsThenReportsCompletionWhichIsNotAClosure() {
        ExecutionResult started = service.start(start(CONTRACTOR_LOGIN, "s1", 4));
        assertThat(started.state()).isEqualTo(InterventionState.IN_PROGRESS);
        assertThat(f.ports.outbox.getLast().event()).isInstanceOf(InterventionStarted.class);

        ExecutionResult reported = service.reportCompletion(completion(CONTRACTOR_LOGIN, "c1", 5, null));

        assertThat(reported.state()).isEqualTo(InterventionState.COMPLETION_REPORTED);
        assertThat(reported.version()).isEqualTo(6);
        assertThat(f.ports.completions).singleElement().satisfies(r -> {
            assertThat(r.number()).isEqualTo(1);
            assertThat(r.reporterRole()).isEqualTo(CompletionReport.BY_CONTRACTOR);
            assertThat(r.summary()).isEqualTo(SUMMARY);
            assertThat(r.performedAt()).isEqualTo(Instant.parse("2026-11-26T10:30:00Z"));
        });
        assertThat(f.ports.outbox.getLast().event()).isInstanceOfSatisfying(CompletionReported.class, e -> {
            assertThat(e.interventionState()).isEqualTo(InterventionState.COMPLETION_REPORTED);
            assertThat(e.toString()).doesNotContain("pipe joint");
        });
    }

    @Test
    void completionMayFollowBookedDirectlyAndAnAdministratorReportsItWithAReason() {
        assertThatThrownBy(() -> service.reportCompletion(completion(ADMIN_A, "c0", 4, null)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactly(new FieldViolation("reason", "required")));

        service.reportCompletion(completion(ADMIN_A, "c1", 4, "The contractor phoned it in."));

        assertThat(f.ports.completions.getFirst().reporterRole()).isEqualTo(CompletionReport.BY_ADMINISTRATOR);
        assertThat(f.ports.completions.getFirst().reason()).contains("The contractor phoned it in.");
    }

    @Test
    void reworkSendsTheReportBackAndKeepsIt() {
        service.reportCompletion(completion(CONTRACTOR_LOGIN, "c1", 4, null));

        ExecutionResult reworked = service.requestRework(rework("r1", 5));

        assertThat(reworked.state()).isEqualTo(InterventionState.IN_PROGRESS);
        assertThat(f.ports.completions).singleElement().satisfies(r -> assertThat(r.rework())
                .hasValueSatisfying(w -> assertThat(w.reason()).isEqualTo("The light still flickers.")));
        assertThat(f.ports.outbox.getLast().event()).isInstanceOf(ReworkRequested.class);

        service.reportCompletion(completion(CONTRACTOR_LOGIN, "c2", 6, null));
        assertThat(f.ports.completions).extracting(CompletionReport::number).containsExactly(1, 2);
    }

    @Test
    void whoThenFieldsThenStateThenVersion() {
        for (UserId other : List.of(RESIDENT_A1, ADMIN_B, new UserId("another-contractor"))) {
            assertThatThrownBy(() -> service.reportCompletion(new CompletionCommand(other, new IdempotencyKey("x"),
                    f.interventionId, 9, "", null, null, "c"))).as(other.value())
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.start(start(ADMIN_A, "s0", 4)))
                .as("only the contractor starts").isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.requestRework(new ReworkCommand(CONTRACTOR_LOGIN, new IdempotencyKey("r0"),
                f.interventionId, 4, "x", "c"))).as("only an administrator").isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reportCompletion(new CompletionCommand(CONTRACTOR_LOGIN,
                new IdempotencyKey("c0"), f.interventionId, 9, "short", "2099-01-01T00:00:00Z", null, "c")))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(new FieldViolation("summary", "too_short"),
                                new FieldViolation("performedAt", "in_future")));
        assertThatThrownBy(() -> service.requestRework(rework("r1", 4)))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("booked"));
        assertThatThrownBy(() -> service.reportCompletion(completion(CONTRACTOR_LOGIN, "c1", 3, null)))
                .isInstanceOf(VersionConflictException.class);
        assertThat(f.ports.completions).isEmpty();
    }

    @Test
    void aResolvedIssueTakesNoMoreWork() {
        f.ports.issues.put(f.issueId, issue(f.issueId, "resolved", 9, false));

        assertThatThrownBy(() -> service.start(start(CONTRACTOR_LOGIN, "s1", 4)))
                .isInstanceOfSatisfying(InvalidTransitionException.class,
                        e -> assertThat(e.currentState()).isEqualTo("resolved"));
    }

    @Test
    void aRepeatedReportReplaysAndAnotherBodyUnderTheKeyIsRefused() {
        ExecutionResult first = service.reportCompletion(completion(CONTRACTOR_LOGIN, "c1", 4, null));

        ExecutionResult again = service.reportCompletion(completion(CONTRACTOR_LOGIN, "c1", 4, null));

        assertThat(again).isEqualTo(new ExecutionResult(first.interventionId(), first.state(), first.version(), true));
        assertThat(f.ports.completions).hasSize(1);
        assertThatThrownBy(() -> service.reportCompletion(new CompletionCommand(CONTRACTOR_LOGIN,
                new IdempotencyKey("c1"), f.interventionId, 4, SUMMARY + " Also", "2026-11-26T10:30:00Z", null, "c")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }
}
