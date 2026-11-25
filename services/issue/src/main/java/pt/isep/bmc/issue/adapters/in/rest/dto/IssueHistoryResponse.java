package pt.isep.bmc.issue.adapters.in.rest.dto;

import java.util.List;

/** {@code GET /issues/{id}/history}: entries oldest first. */
public record IssueHistoryResponse(List<Entry> items) {

    /**
     * @param actorRole {@code resident}, {@code administrator}, {@code contractor} or {@code system}; never who
     * @param category  the decided category on {@code triage_decided}, the suggested one on
     *                  {@code triage_suggested}, otherwise {@code null}
     * @param reason    internal: {@code null} for residents
     * @param origin    on {@code triage_suggested} only: {@code ai}, {@code rules} or {@code fallback}
     * @param applied   on {@code triage_suggested} only: {@code false} for a suggestion that arrived
     *                  late and changed nothing
     */
    public record Entry(
            int sequence,
            String type,
            String visibility,
            String actorRole,
            String occurredAt,
            String category,
            String urgency,
            String reason,
            String origin,
            Boolean applied) {

        @Override
        public String toString() {
            return "Entry[sequence=" + sequence + ", type=" + type + ", reason=redacted]";
        }
    }
}
