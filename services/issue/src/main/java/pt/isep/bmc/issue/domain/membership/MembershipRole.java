package pt.isep.bmc.issue.domain.membership;

/** Building role of a membership (technical design 2.1). */
public enum MembershipRole {
    RESIDENT("resident"),
    ADMINISTRATOR("administrator");

    private final String value;

    MembershipRole(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MembershipRole fromValue(String value) {
        for (MembershipRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown membership role: " + value);
    }
}
