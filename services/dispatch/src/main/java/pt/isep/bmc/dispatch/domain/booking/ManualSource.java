package pt.isep.bmc.dispatch.domain.booking;

/** How a booking confirmed by hand was agreed (US-08). */
public enum ManualSource {
    PHONE("phone"),
    EMAIL("email"),
    IN_PERSON("in_person");

    private final String value;

    ManualSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ManualSource fromValue(String value) {
        for (ManualSource item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unknown ManualSource");
    }
}
