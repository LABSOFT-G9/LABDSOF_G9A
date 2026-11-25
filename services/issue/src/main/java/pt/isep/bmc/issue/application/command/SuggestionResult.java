package pt.isep.bmc.issue.application.command;

/** What recording a triage suggestion did. */
public enum SuggestionResult {
    /** The issue moved to {@code in_triage}. */
    APPLIED,
    /** Late: stored and shown in the internal history; the issue did not change. */
    RECORDED_LATE,
    /** The event was recorded before: nothing was written. */
    DUPLICATE
}
