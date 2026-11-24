package pt.isep.bmc.issue.domain.membership;

/** A revoked membership keeps its row; rows are never deleted (technical design 2.1). */
public enum MembershipStatus {
    ACTIVE("active"),
    REVOKED("revoked");

    private final String value;

    MembershipStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MembershipStatus fromValue(String value) {
        for (MembershipStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown membership status: " + value);
    }
}
