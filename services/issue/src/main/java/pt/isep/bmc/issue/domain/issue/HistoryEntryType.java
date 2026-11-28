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
    CRITICAL_ALERT_RAISED("critical_alert_raised"),
    // From Dispatch's events (US-07-03, technical design 3.3): one entry per event, applied or late.
    /** The administrator approved a request; it was sent to the contractor. Public. */
    INTERVENTION_APPROVED("intervention_approved"),
    /** The contractor accepted the request. Public. */
    CONTRACTOR_ACCEPTED("contractor_accepted"),
    /** The contractor answered with other terms. Internal. */
    CONTRACTOR_COUNTERPROPOSED("contractor_counterproposed"),
    /** The contractor declined; the issue needs a contractor again. Public; the reason code is internal. */
    CONTRACTOR_DECLINED("contractor_declined"),
    /** The administrator changed the terms of a request. Internal. */
    INTERVENTION_TERMS_CHANGED("intervention_terms_changed"),
    /** The calendar confirmed the visit. Public, with the window. */
    BOOKING_CONFIRMED("booking_confirmed"),
    /** The administrator confirmed the visit by hand; never shown as synchronised. Public, with the window. */
    BOOKING_CONFIRMED_MANUALLY("booking_confirmed_manually"),
    /** The booking is requested, uncertain, rejected, failed or a discrepancy. Internal. */
    BOOKING_STATE_CHANGED("booking_state_changed"),
    /** The contractor started the work. Public. */
    INTERVENTION_STARTED("intervention_started"),
    /** The contractor reported the work as completed; not a closure. Public. */
    COMPLETION_REPORTED("completion_reported"),
    /** The administrator asked for the work to be redone. Public. */
    REWORK_REQUESTED("rework_requested"),
    /** The intervention is being cancelled at the calendar. Public; the reason code is internal. */
    INTERVENTION_CANCELLATION_PENDING("intervention_cancellation_pending"),
    /** The intervention was cancelled. Public; the reason code is internal. */
    INTERVENTION_CANCELLED("intervention_cancelled");

    private final String value;

    HistoryEntryType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
