package pt.isep.bmc.issue.application.command;

/** What one delivery of a Dispatch event did. */
public enum DispatchEventResult {
    /** It moved the intervention, and the issue when its state maps to another. */
    APPLIED,
    /** Shown in history only: a booking's own change, late, of an ended intervention, or of a closed issue. */
    HISTORY_ONLY,
    /** It fits no rule and was kept for inspection, with no effect. */
    FAILED,
    /**
     * Of a second intervention while another is active: the event that ended the first one is still on its way.
     * Nothing was written; the listener delivers it again later, and to the dead-letter queue after its attempts.
     */
    RETRY_LATER,
    /** The same event was already received; nothing changed. */
    DUPLICATE
}
