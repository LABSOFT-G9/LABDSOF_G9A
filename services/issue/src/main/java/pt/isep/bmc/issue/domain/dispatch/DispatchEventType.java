package pt.isep.bmc.issue.domain.dispatch;

import java.util.Optional;

/** The Dispatch events Issue applies (technical design 4.4), by their CloudEvents {@code type}. */
public enum DispatchEventType {
    INTERVENTION_APPROVED("InterventionApproved"),
    CONTRACTOR_RESPONDED("ContractorResponded"),
    INTERVENTION_DECLINED("InterventionDeclined"),
    INTERVENTION_TERMS_CHANGED("InterventionTermsChanged"),
    BOOKING_CONFIRMED("BookingConfirmed"),
    BOOKING_STATE_CHANGED("BookingStateChanged"),
    INTERVENTION_STARTED("InterventionStarted"),
    COMPLETION_REPORTED("CompletionReported"),
    REWORK_REQUESTED("ReworkRequested"),
    INTERVENTION_CANCELLATION_PENDING("InterventionCancellationPending"),
    INTERVENTION_CANCELLED("InterventionCancelled");

    private final String value;

    DispatchEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<DispatchEventType> fromValue(String value) {
        for (DispatchEventType type : values()) {
            if (type.value.equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
