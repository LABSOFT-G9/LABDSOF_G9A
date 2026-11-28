package pt.isep.bmc.issue.application.command;

/** What one delivery of a Dispatch event did. */
public enum DispatchEventResult {
    /** It moved the intervention, and the issue when its state maps to another. */
    APPLIED,
    /** Shown in history only: late, of an ended intervention, or of a closed issue. */
    HISTORY_ONLY,
    /** It fits no rule and was kept for inspection, with no effect. */
    FAILED,
    /** The same event was already received; nothing changed. */
    DUPLICATE
}
