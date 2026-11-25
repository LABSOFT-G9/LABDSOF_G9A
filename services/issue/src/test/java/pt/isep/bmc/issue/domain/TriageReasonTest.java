package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.domain.issue.TriageReason;

class TriageReasonTest {

    @Test
    void trimsAndAcceptsOneToFiveHundredCharacters() {
        assertThat(TriageReason.of("  Water near an electrical fitting \n").value())
                .isEqualTo("Water near an electrical fitting");
        assertThat(TriageReason.violation("x")).isEmpty();
        assertThat(TriageReason.violation("x".repeat(500))).isEmpty();
        // Counted in code points: 500 emoji are 500 characters, not 1,000.
        assertThat(TriageReason.violation("🔧".repeat(500))).isEmpty();
        assertThat(TriageReason.violation("line one\nline two\ttabbed")).isEmpty();
    }

    @Test
    void rejectsMissingTooLongAndControlCharacters() {
        assertThat(TriageReason.violation(null)).contains("required");
        assertThat(TriageReason.violation("")).contains("required");
        assertThat(TriageReason.violation("  \n\t ")).contains("required");
        assertThat(TriageReason.violation("x".repeat(501))).contains("too_long");
        assertThat(TriageReason.violation("🔧".repeat(501))).contains("too_long");
        assertThat(TriageReason.violation("bell\u0007")).contains("invalid_characters");
        assertThat(TriageReason.violation("lone surrogate \uD83D here")).contains("invalid_characters");
        assertThatThrownBy(() -> TriageReason.of(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverPrintsItsText() {
        TriageReason reason = TriageReason.of("The resident in 2B says the socket sparks");
        assertThat(reason.toString()).isEqualTo("TriageReason[redacted]").doesNotContain("2B");
        assertThat(reason).isEqualTo(TriageReason.restore("The resident in 2B says the socket sparks"));
    }
}
