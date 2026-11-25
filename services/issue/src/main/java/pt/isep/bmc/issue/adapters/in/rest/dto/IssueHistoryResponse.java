package pt.isep.bmc.issue.adapters.in.rest.dto;

import java.util.List;

/** {@code GET /issues/{id}/history}: entries oldest first. */
public record IssueHistoryResponse(List<Entry> items) {

    /**
     * @param actorRole {@code resident}, {@code administrator}, {@code contractor} or {@code system}; never who
     * @param category  on {@code triage_decided} only, otherwise {@code null}
     * @param reason    internal: {@code null} for residents
     */
    public record Entry(
            int sequence,
            String type,
            String visibility,
            String actorRole,
            String occurredAt,
            String category,
            String urgency,
            String reason) {

        @Override
        public String toString() {
            return "Entry[sequence=" + sequence + ", type=" + type + ", reason=redacted]";
        }
    }
}
