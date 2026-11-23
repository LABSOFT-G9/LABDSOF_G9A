package pt.isep.bmc.issue.domain.issue;

/** Residents see only public entries (technical design 2.1). */
public enum HistoryVisibility {
    PUBLIC("public"),
    INTERNAL("internal");

    private final String value;

    HistoryVisibility(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
