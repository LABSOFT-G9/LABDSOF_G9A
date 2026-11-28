package pt.isep.bmc.dispatch.domain.booking;

/** Booking states (technical design 4.5). {@code rejected}, {@code failed} and {@code cancelled} end a booking; the others are the intervention's active booking. */
public enum BookingState {
    REQUESTED("requested"),
    UNCERTAIN("uncertain"),
    CONFIRMED("confirmed"),
    REJECTED("rejected"),
    FAILED("failed"),
    DISCREPANCY("discrepancy"),
    CANCELLED("cancelled");

    private final String value;

    BookingState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static BookingState fromValue(String value) {
        for (BookingState item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unknown BookingState");
    }
}
