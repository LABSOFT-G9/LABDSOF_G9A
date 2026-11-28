package pt.isep.bmc.dispatch.adapters.in.rest.dto;

import java.util.List;

/**
 * A booking as the building's administrators see it (US-08).
 *
 * @param source        {@code calendar} or {@code manual}; null until confirmed
 * @param pendingAction what the worker does next ({@code create}, {@code reconcile}, {@code verify},
 *                      {@code orphan_check}, {@code cancel}); null when nothing is pending
 * @param manual        how and why it was confirmed by hand; null otherwise
 */
public record BookingResponse(
        String bookingId,
        String state,
        String source,
        InterventionResponse.Window requestedWindow,
        InterventionResponse.Window bookedWindow,
        String externalRef,
        String reasonCode,
        String pendingAction,
        String lastReconciledAt,
        Manual manual,
        List<Call> calls,
        String createdAt,
        String updatedAt) {

    public record Manual(String source, String reason, String confirmedAt) {
    }

    public record Call(String action, String outcome, String at) {
    }
}
