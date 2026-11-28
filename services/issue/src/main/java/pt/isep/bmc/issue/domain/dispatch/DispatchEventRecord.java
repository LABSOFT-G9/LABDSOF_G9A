package pt.isep.bmc.issue.domain.dispatch;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What Issue did with one Dispatch event, kept for good: its ID makes a
 * repeated delivery a no-op, and a {@code failed} one waits for inspection
 * (technical design 3.3).
 *
 * @param reason          why the event was only recorded or failed; empty when applied
 * @param historySequence the history entry that shows the event; empty when it failed
 */
public record DispatchEventRecord(
        DispatchEvent event,
        Outcome outcome,
        Optional<String> reason,
        Optional<Integer> historySequence,
        Instant receivedAt) {

    /** What happened to the event. */
    public enum Outcome {
        /** It changed the intervention's projection, and the issue state when it maps to another. */
        APPLIED("applied"),
        /** Shown in history only: older than what was applied, of an earlier intervention, or of a closed issue. */
        HISTORY_ONLY("history_only"),
        /** It fits no rule; kept for inspection, with no effect. */
        FAILED("failed");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** No Dispatch event names an issue Issue does not have. */
    public static final String UNKNOWN_ISSUE = "unknown_issue";
    /** The event's building is not the issue's. */
    public static final String BUILDING_MISMATCH = "building_mismatch";
    /** The issue already has another active intervention. */
    public static final String SECOND_INTERVENTION = "second_intervention";
    /** A new intervention for an issue that is not {@code triaged}. */
    public static final String NOT_DISPATCHABLE = "not_dispatchable";
    /** Not newer than the last version applied for that intervention. */
    public static final String STALE_VERSION = "stale_version";
    /** An intervention that already ended. */
    public static final String ENDED_INTERVENTION = "ended_intervention";
    /** The issue is resolved or cancelled. */
    public static final String ISSUE_CLOSED = "issue_closed";

    public DispatchEventRecord {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(historySequence, "historySequence");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if ((outcome == Outcome.APPLIED) != reason.isEmpty()) {
            throw new IllegalArgumentException("only an event that was not applied has a reason");
        }
        if ((outcome == Outcome.FAILED) != historySequence.isEmpty()) {
            throw new IllegalArgumentException("every event but a failed one has a history entry");
        }
    }

    /** An event that names an issue Issue does not have. */
    public static DispatchEventRecord unknownIssue(DispatchEvent event, Instant now) {
        return new DispatchEventRecord(event, Outcome.FAILED, Optional.of(UNKNOWN_ISSUE), Optional.empty(), now);
    }
}
