package pt.isep.bmc.dispatch.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.BookingCommandResponse;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.CancellationRequest;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.CreateInterventionRequest;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.ManualConfirmationRequest;
import pt.isep.bmc.dispatch.application.command.BookingCommandResult;
import pt.isep.bmc.dispatch.application.command.CancelInterventionCommand;
import pt.isep.bmc.dispatch.application.command.ManualConfirmationCommand;
import pt.isep.bmc.dispatch.application.command.RequestBookingCommand;
import pt.isep.bmc.dispatch.application.error.NotFoundException;
import pt.isep.bmc.dispatch.application.idempotency.IdempotencyKey;
import pt.isep.bmc.dispatch.application.port.in.BookingUseCases;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;

/**
 * The administrator's booking commands (US-08) and the cancellation of an
 * intervention (technical design 3.2 and 4.2). The commands that change the
 * intervention need {@code If-Match} and an {@code Idempotency-Key}; the
 * reconciliation request needs neither, because it only asks for a check.
 */
@RestController
@RequestMapping(path = "/interventions/{interventionId}", produces = MediaType.APPLICATION_JSON_VALUE)
class BookingController {

    private final BookingUseCases bookings;

    BookingController(BookingUseCases bookings) {
        this.bookings = bookings;
    }

    /** Confirms the booking by hand: {@code booked}, source {@code manual}. */
    @PostMapping(path = "/manual-confirmation", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BookingCommandResponse> confirmManually(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) ManualConfirmationRequest body,
            HttpServletRequest request) {
        ManualConfirmationRequest manual = body == null ? new ManualConfirmationRequest(null, null, null) : body;
        CreateInterventionRequest.WindowBody window = manual.window() == null
                ? new CreateInterventionRequest.WindowBody(null, null)
                : manual.window();
        return answer(bookings.confirmManually(new ManualConfirmationCommand(Actors.of(jwt),
                new IdempotencyKey(idempotencyKey), id(interventionId), EntityVersions.parseIfMatch(ifMatch),
                window.start(), window.end(), manual.source(), manual.reason(), CorrelationIdFilter.of(request))),
                HttpStatus.OK);
    }

    /** Another booking request for the agreed window, after the last one was {@code rejected} or {@code failed}. */
    @PostMapping("/booking")
    ResponseEntity<BookingCommandResponse> requestAgain(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            HttpServletRequest request) {
        return answer(bookings.requestAgain(new RequestBookingCommand(Actors.of(jwt),
                new IdempotencyKey(idempotencyKey), id(interventionId), EntityVersions.parseIfMatch(ifMatch),
                CorrelationIdFilter.of(request))), HttpStatus.OK);
    }

    /** Asks for a check of the confirmed booking now: {@code 202}, the worker calls the calendar. */
    @PostMapping("/booking/reconciliation")
    ResponseEntity<BookingCommandResponse> reconcile(@AuthenticationPrincipal Jwt jwt, @PathVariable String interventionId) {
        return answer(bookings.reconcile(Actors.of(jwt), id(interventionId)), HttpStatus.ACCEPTED);
    }

    /** Cancels: {@code cancelled}, or {@code cancellation_pending} while the calendar booking is cancelled. */
    @PostMapping(path = "/cancellation", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BookingCommandResponse> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String interventionId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) CancellationRequest body,
            HttpServletRequest request) {
        CancellationRequest cancellation = body == null ? new CancellationRequest(null, null) : body;
        return answer(bookings.cancel(new CancelInterventionCommand(Actors.of(jwt), new IdempotencyKey(idempotencyKey),
                id(interventionId), EntityVersions.parseIfMatch(ifMatch), cancellation.reasonCode(),
                cancellation.note(), CorrelationIdFilter.of(request))), HttpStatus.OK);
    }

    private static ResponseEntity<BookingCommandResponse> answer(BookingCommandResult result, HttpStatus status) {
        return ResponseEntity.status(status)
                .eTag(Long.toString(result.version()))
                .body(new BookingCommandResponse(result.interventionId().toString(), result.state().value(),
                        result.version(), result.bookingState().map(BookingState::value).orElse(null)));
    }

    private static InterventionId id(String raw) {
        return new InterventionId(RequestIds.parse(raw).orElseThrow(NotFoundException::new));
    }
}
