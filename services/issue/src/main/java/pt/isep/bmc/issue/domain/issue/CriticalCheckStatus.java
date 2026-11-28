package pt.isep.bmc.issue.domain.issue;

/**
 * Status of Issue's own {@code critical-alerts-v1} check (technical design 5,
 * step 1). {@link #PENDING} means the rule set did not run on the issue's
 * report: it failed, or the issue is older than the check. It is never shown
 * as "checked, nothing found".
 */
public enum CriticalCheckStatus {
    PENDING("pending"),
    COMPLETED("completed");

    private final String value;

    CriticalCheckStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CriticalCheckStatus fromValue(String value) {
        for (CriticalCheckStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown critical check status: " + value);
    }
}
