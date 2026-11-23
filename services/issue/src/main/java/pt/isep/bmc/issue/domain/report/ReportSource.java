package pt.isep.bmc.issue.domain.report;

/** Who submitted a report (technical design 2.1). */
public enum ReportSource {
    RESIDENT("resident"),
    ADMINISTRATOR_ON_BEHALF("administrator_on_behalf");

    private final String value;

    ReportSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ReportSource fromValue(String value) {
        for (ReportSource source : values()) {
            if (source.value.equals(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("unknown report source: " + value);
    }
}
