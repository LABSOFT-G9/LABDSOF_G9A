package pt.isep.bmc.dispatch.domain.booking;

/** Who confirmed a booking: the calendar, or an administrator by hand. A manual confirmation is never shown as synchronised. */
public enum BookingSource {
    CALENDAR("calendar"),
    MANUAL("manual");

    private final String value;

    BookingSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static BookingSource fromValue(String value) {
        for (BookingSource item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unknown BookingSource");
    }
}
