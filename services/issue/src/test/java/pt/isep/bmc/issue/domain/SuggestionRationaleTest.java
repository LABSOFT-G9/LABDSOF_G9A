package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import pt.isep.bmc.issue.domain.issue.SuggestionRationale;

class SuggestionRationaleTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\t"})
    void aMissingOrBlankRationaleIsRequired(String raw) {
        assertThat(SuggestionRationale.violation(raw)).contains("required");
    }

    @Test
    void lengthIsCountedInCodePointsAfterTrimming() {
        assertThat(SuggestionRationale.violation("x")).isEmpty();
        assertThat(SuggestionRationale.violation("x".repeat(240))).isEmpty();
        assertThat(SuggestionRationale.violation("  " + "x".repeat(240) + "  ")).isEmpty();
        assertThat(SuggestionRationale.violation("x".repeat(241))).contains("too_long");
        // 240 characters outside the basic plane are 480 Java chars, and still fit.
        assertThat(SuggestionRationale.violation("💧".repeat(240))).isEmpty();
        assertThat(SuggestionRationale.violation("💧".repeat(241))).contains("too_long");
    }

    @Test
    void controlCharactersAndLoneSurrogatesAreRejected() {
        assertThat(SuggestionRationale.violation("line one\nline two\ttabbed")).isEmpty();
        assertThat(SuggestionRationale.violation("bell\u0007")).contains("invalid_characters");
        assertThat(SuggestionRationale.violation("lone \uD83D surrogate")).contains("invalid_characters");
    }

    @Test
    void theTextIsTrimmedAndNeverPrinted() {
        SuggestionRationale rationale = SuggestionRationale.of("  Rules matched \"leak\".  ");

        assertThat(rationale.value()).isEqualTo("Rules matched \"leak\".");
        assertThat(rationale).isEqualTo(SuggestionRationale.restore("Rules matched \"leak\"."));
        assertThat(rationale.toString()).doesNotContain("leak");
        assertThatThrownBy(() -> SuggestionRationale.of(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
