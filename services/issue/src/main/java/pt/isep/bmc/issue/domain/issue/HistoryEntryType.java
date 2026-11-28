package pt.isep.bmc.issue.domain.issue;

/** Kinds of history entry. Later stories add intervention and closure entries. */
public enum HistoryEntryType {
    REPORT_RECEIVED("report_received"),
    /** A triage suggestion arrived (AI, rules or fallback), applied or late. Internal. */
    TRIAGE_SUGGESTED("triage_suggested"),
    /** An administrator decided category and urgency. Public; the reason is not. */
    TRIAGE_DECIDED("triage_decided"),
    /** An administrator published or replaced the shared summary. Public. */
    SUMMARY_PUBLISHED("summary_published"),
    /** A rule of Issue's own critical check matched a report. Internal; one entry per rule. */
    CRITICAL_ALERT_RAISED("critical_alert_raised");

    private final String value;

    HistoryEntryType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
