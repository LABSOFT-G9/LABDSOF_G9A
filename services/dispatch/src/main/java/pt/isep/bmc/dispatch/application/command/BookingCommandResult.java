package pt.isep.bmc.dispatch.application.command;

import java.util.Optional;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;

/**
 * The result of a booking command or a cancellation.
 *
 * @param bookingState the intervention's latest booking after the command, if it has one
 * @param replayed     true when an earlier result was returned for a repeated idempotency key
 */
public record BookingCommandResult(
        InterventionId interventionId, InterventionState state, long version, Optional<BookingState> bookingState,
        boolean replayed) {
}
