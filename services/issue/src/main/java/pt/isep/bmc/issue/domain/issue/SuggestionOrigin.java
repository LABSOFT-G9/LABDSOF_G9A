package pt.isep.bmc.issue.domain.issue;

import java.util.Arrays;
import java.util.Optional;

/**
 * Who produced a triage suggestion (technical design 2.1): the AI provider,
 * the versioned rules, or the rules after the provider's result could not be
 * used. Never a person: only people decide.
 */
public enum SuggestionOrigin {
    AI("ai"),
    RULES("rules"),
    FALLBACK("fallback");

    private final String value;

    SuggestionOrigin(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<SuggestionOrigin> fromValue(String value) {
        return Arrays.stream(values()).filter(origin -> origin.value.equals(value)).findFirst();
    }
}
