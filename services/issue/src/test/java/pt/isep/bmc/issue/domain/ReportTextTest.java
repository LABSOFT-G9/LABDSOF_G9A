package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import pt.isep.bmc.issue.domain.report.ReportText;
import pt.isep.bmc.issue.domain.shared.ValidationException;

class ReportTextTest {

    @Test
    void trimsAndKeepsValidText() {
        assertThat(ReportText.of("   Water dripping in the garage.  \n").value())
                .isEqualTo("Water dripping in the garage.");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\t "})
    void rejectsMissingOrBlankText(String raw) {
        assertReason(raw, "required");
    }

    @Test
    void enforcesTenToFourThousandCodePointsAfterTrimming() {
        assertReason("  123456789  ", "too_short");
        assertThat(ReportText.of("1234567890").value()).hasSize(10);
        assertThat(ReportText.of("x".repeat(4_000)).value()).hasSize(4_000);
        assertReason("x".repeat(4_001), "too_long");
    }

    @Test
    void countsCodePointsNotUtf16Units() {
        String emoji = "💧"; // one code point, two chars
        assertThat(ReportText.of(emoji.repeat(10)).value()).hasSize(20);
        assertReason(emoji.repeat(4_001), "too_long");
    }

    @Test
    void rejectsControlCharactersButKeepsLineBreaksAndTabs() {
        assertThat(ReportText.of("Line one\nline two\tok\r\n").value()).contains("\n");
        assertReason("Leak in garage \u0000 level -1", "invalid_characters");
        assertReason("Leak in garage \u001B[31m level -1", "invalid_characters");
    }

    @Test
    void rejectsLoneSurrogatesThatMysqlWouldStoreAsQuestionMarks() {
        assertReason("Leak in garage \uD800 level -1", "invalid_characters");
        assertReason("Leak in garage \uDC00 level -1", "invalid_characters");
    }

    @Test
    void neverPrintsTheTextAndErrorsNeverEchoIt() {
        String secret = "Flat 2B owner Jane Doe leaks water";
        assertThat(ReportText.of(secret).toString()).doesNotContain("Jane");
        assertThatThrownBy(() -> ReportText.of(secret + "x".repeat(4_000)))
                .hasMessageNotContaining("Jane");
    }

    private static void assertReason(String raw, String reason) {
        assertThatThrownBy(() -> ReportText.of(raw))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.violations()).singleElement().satisfies(v -> {
                        assertThat(v.field()).isEqualTo("text");
                        assertThat(v.reason()).isEqualTo(reason);
                    });
                });
    }
}
