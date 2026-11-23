package pt.isep.bmc.issue.domain.building;

/** Kind of area (technical design 2.1): a common area or a unit reference. */
public enum AreaKind {
    COMMON_AREA("common_area"),
    UNIT("unit");

    private final String value;

    AreaKind(String value) {
        this.value = value;
    }

    /** Stable lower-case name used in the API, the database and events. */
    public String value() {
        return value;
    }

    public static AreaKind fromValue(String value) {
        for (AreaKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown area kind: " + value);
    }
}
