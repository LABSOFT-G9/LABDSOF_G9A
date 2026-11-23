package pt.isep.bmc.issue.domain.issue;

/** Kinds of history entry. Later stories add triage, intervention and closure entries. */
public enum HistoryEntryType {
    REPORT_RECEIVED("report_received");

    private final String value;

    HistoryEntryType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
