package pt.isep.bmc.calendarsim.booking;

/** The simulator's three operations, each with its own queue of scripted outcomes. */
public enum Operation {
    CREATE("create"),
    QUERY("query"),
    CANCEL("cancel");

    private final String value;

    Operation(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Operation fromValue(String value) {
        for (Operation operation : values()) {
            if (operation.value.equals(value)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("unknown operation");
    }
}
