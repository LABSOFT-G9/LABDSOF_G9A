package pt.isep.bmc.issue.domain.issue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.issue.domain.shared.FieldViolation;

/**
 * What produced a suggestion, as the suggester reported it (technical design
 * 2.1 and 4.4). Issue stores and shows these labels; it does not interpret
 * them. Each is absent when that part did not run.
 *
 * @param modelVersion        absent when no provider was called
 * @param rulesVersion        absent when the rules did not run
 * @param outputSchemaVersion version of the provider-output schema, not of the event
 * @param failureCode         why the provider's result was not used; with origin {@code fallback}
 */
public record SuggestionProvenance(
        Optional<String> modelVersion,
        Optional<String> promptVersion,
        Optional<String> rulesVersion,
        Optional<String> outputSchemaVersion,
        Optional<String> failureCode) {

    /** Longest label stored. */
    public static final int MAX_LENGTH = 100;

    static final String FIELD_MODEL_VERSION = "modelVersion";
    static final String FIELD_PROMPT_VERSION = "promptVersion";
    static final String FIELD_RULES_VERSION = "rulesVersion";
    static final String FIELD_OUTPUT_SCHEMA_VERSION = "outputSchemaVersion";
    static final String FIELD_FAILURE_CODE = "failureCode";

    public SuggestionProvenance {
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(rulesVersion, "rulesVersion");
        Objects.requireNonNull(outputSchemaVersion, "outputSchemaVersion");
        Objects.requireNonNull(failureCode, "failureCode");
    }

    /** Every invalid label of a received suggestion; empty when all are valid. */
    static List<FieldViolation> violations(SuggestionInput input) {
        List<FieldViolation> violations = new ArrayList<>();
        check(FIELD_MODEL_VERSION, input.modelVersion(), violations);
        check(FIELD_PROMPT_VERSION, input.promptVersion(), violations);
        check(FIELD_RULES_VERSION, input.rulesVersion(), violations);
        check(FIELD_OUTPUT_SCHEMA_VERSION, input.outputSchemaVersion(), violations);
        check(FIELD_FAILURE_CODE, input.failureCode(), violations);
        return violations;
    }

    /** Trims the labels of a received suggestion; check it with {@link #violations} first. */
    static SuggestionProvenance of(SuggestionInput input) {
        return new SuggestionProvenance(
                label(input.modelVersion()),
                label(input.promptVersion()),
                label(input.rulesVersion()),
                label(input.outputSchemaVersion()),
                label(input.failureCode()));
    }

    private static void check(String field, String raw, List<FieldViolation> violations) {
        label(raw).ifPresent(label -> {
            if (label.length() > MAX_LENGTH) {
                violations.add(new FieldViolation(field, "too_long"));
            } else if (!label.chars().allMatch(c -> c > ' ' && c < 0x7f)) {
                // A label is a short identifier: visible ASCII, no spaces.
                violations.add(new FieldViolation(field, "invalid_characters"));
            }
        });
    }

    /** A blank label is an absent one. */
    private static Optional<String> label(String raw) {
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw.strip());
    }
}
