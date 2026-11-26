package pt.isep.bmc.dispatch.domain.booking;

/** What the booking worker must do next with the calendar. {@code create} sends the request; {@code reconcile} looks it up after an uncertain outcome and re-sends only if it is absent; {@code verify} checks that a confirmed booking still exists; {@code orphan_check} looks for an automatic booking made before a manual confirmation; {@code cancel} cancels it at the calendar. */
public enum BookingAction {
    CREATE("create"),
    RECONCILE("reconcile"),
    VERIFY("verify"),
    ORPHAN_CHECK("orphan_check"),
    CANCEL("cancel");

    private final String value;

    BookingAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static BookingAction fromValue(String value) {
        for (BookingAction item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unknown BookingAction");
    }
}
