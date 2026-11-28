package pt.isep.bmc.calendarsim.booking;

import java.util.Objects;

/**
 * A request to book, as Dispatch sends it (technical design 4.5): no names,
 * addresses or report text.
 *
 * @param requestId Dispatch's booking ID, also the idempotency key
 */
public record BookingRequest(String requestId, String contractorRef, Window window, String buildingCode,
        String summary) {

    public BookingRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(contractorRef, "contractorRef");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(buildingCode, "buildingCode");
        Objects.requireNonNull(summary, "summary");
    }

    /** Never prints the summary. */
    @Override
    public String toString() {
        return "BookingRequest[requestId=" + requestId + "]";
    }
}
