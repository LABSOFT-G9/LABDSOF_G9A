package pt.isep.bmc.issue.application.port.out;

import java.util.UUID;
import pt.isep.bmc.issue.domain.issue.TriageSuggestion;

/** Triage suggestions are appended, never changed or removed. */
public interface TriageSuggestionRepository {

    /** Whether the suggestion carried by this event was recorded before. */
    boolean existsByEventId(UUID eventId);

    void add(TriageSuggestion suggestion);
}
