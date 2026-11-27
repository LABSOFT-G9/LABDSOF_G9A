package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/**
 * A completion report (US-09).
 *
 * @param reason            why an administrator reported it for the contractor; null otherwise, and never shown
 *                          to the contractor
 * @param reworkReason      why it was sent back; null unless it was
 */
public record CompletionResponse(
        int number,
        String summary,
        String performedAt,
        String reporterRole,
        String reason,
        String reportedAt,
        String reworkReason,
        String reworkRequestedAt) {
}
