package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.domain.issue.SummaryText;

class SummaryTextTest {

    @Test
    void trimsAndAcceptsTenToFiveHundredCharacters() {
        assertThat(SummaryText.of("  The garage door does not close.  \n").value())
                .isEqualTo("The garage door does not close.");
        assertThat(SummaryText.violation("x".repeat(10))).isEmpty();
        assertThat(SummaryText.violation("x".repeat(500))).isEmpty();
        // Counted in code points: 500 emoji are 500 characters, not 1,000.
        assertThat(SummaryText.violation("🔧".repeat(500))).isEmpty();
        assertThat(SummaryText.violation("line one\nline two\ttabbed")).isEmpty();
    }

    @Test
    void rejectsMissingTooShortTooLongAndControlCharacters() {
        assertThat(SummaryText.violation(null)).contains("required");
        assertThat(SummaryText.violation("")).contains("required");
        assertThat(SummaryText.violation("  \n\t ")).contains("required");
        assertThat(SummaryText.violation("x".repeat(9))).contains("too_short");
        // Trimmed before it is measured.
        assertThat(SummaryText.violation("   " + "x".repeat(9) + "   ")).contains("too_short");
        assertThat(SummaryText.violation("x".repeat(501))).contains("too_long");
        assertThat(SummaryText.violation("🔧".repeat(501))).contains("too_long");
        assertThat(SummaryText.violation("a bell rings\u0007")).contains("invalid_characters");
        assertThat(SummaryText.violation("lone surrogate \uD83D here")).contains("invalid_characters");
        assertThatThrownBy(() -> SummaryText.of("short")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotPrintItsText() {
        SummaryText text = SummaryText.of("The garage door does not close.");
        assertThat(text.toString()).doesNotContain("garage");
        assertThat(text).isEqualTo(SummaryText.restore("The garage door does not close."));
    }
}
