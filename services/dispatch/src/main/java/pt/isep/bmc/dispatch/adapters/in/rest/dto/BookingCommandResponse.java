package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/**
 * Answer of the booking commands and the cancellation.
 *
 * @param bookingState the current booking's state after the command; null when there is none or for a replay
 */
public record BookingCommandResponse(String interventionId, String state, long version, String bookingState) {
}
