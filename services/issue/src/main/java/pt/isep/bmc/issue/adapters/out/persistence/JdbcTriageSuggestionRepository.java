package pt.isep.bmc.issue.adapters.out.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.TriageSuggestionRepository;
import pt.isep.bmc.issue.domain.issue.TriageSuggestion;

/**
 * Triage suggestions in {@code triage_suggestions}. Plain SQL in the caller's
 * transaction. The event ID is the primary key, so storing the same event
 * twice fails instead of adding a second suggestion.
 */
@Repository
class JdbcTriageSuggestionRepository implements TriageSuggestionRepository {

    private final JdbcClient jdbc;

    JdbcTriageSuggestionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsByEventId(UUID eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM triage_suggestions WHERE event_id = :eventId")
                .param("eventId", eventId.toString())
                .query(Long.class)
                .single() > 0;
    }

    @Override
    public void add(TriageSuggestion suggestion) {
        jdbc.sql("""
                        INSERT INTO triage_suggestions (event_id, issue_id, history_sequence, source_version, applied,
                                                        category, urgency, rationale, needs_review, origin,
                                                        model_version, prompt_version, rules_version,
                                                        output_schema_version, failure_code, recorded_at)
                        VALUES (:eventId, :issueId, :historySequence, :sourceVersion, :applied,
                                :category, :urgency, :rationale, :needsReview, :origin,
                                :modelVersion, :promptVersion, :rulesVersion,
                                :outputSchemaVersion, :failureCode, :recordedAt)
                        """)
                .param("eventId", suggestion.eventId().toString())
                .param("issueId", suggestion.issueId().toString())
                .param("historySequence", suggestion.historySequence())
                .param("sourceVersion", suggestion.sourceVersion())
                .param("applied", suggestion.applied())
                .param("category", suggestion.category().value())
                .param("urgency", suggestion.urgency().value())
                .param("rationale", suggestion.rationale().value())
                .param("needsReview", suggestion.needsReview())
                .param("origin", suggestion.origin().value())
                .param("modelVersion", suggestion.provenance().modelVersion().orElse(null))
                .param("promptVersion", suggestion.provenance().promptVersion().orElse(null))
                .param("rulesVersion", suggestion.provenance().rulesVersion().orElse(null))
                .param("outputSchemaVersion", suggestion.provenance().outputSchemaVersion().orElse(null))
                .param("failureCode", suggestion.provenance().failureCode().orElse(null))
                .param("recordedAt", Timestamps.toColumn(suggestion.recordedAt()))
                .update();
    }
}
