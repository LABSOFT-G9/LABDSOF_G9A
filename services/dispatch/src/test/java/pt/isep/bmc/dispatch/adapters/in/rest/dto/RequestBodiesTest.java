package pt.isep.bmc.dispatch.adapters.in.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Spring logs a request body's {@code toString()} at DEBUG, truncated only
 * after about 100 characters, so a body with free text must not print it.
 */
class RequestBodiesTest {

    static final String MARKER = "PrivateMarker";
    static final CreateInterventionRequest.WindowBody WINDOW =
            new CreateInterventionRequest.WindowBody("2099-11-05T09:00:00Z", "2099-11-05T12:00:00Z");

    @Test
    void bodiesWithFreeTextNeverPrintIt() {
        assertThat(new CreateInterventionRequest("i", "c", MARKER, WINDOW, MARKER).toString()).doesNotContain(MARKER);
        assertThat(new ChangeTermsRequest(MARKER, WINDOW, MARKER).toString()).doesNotContain(MARKER);
        assertThat(new RespondRequest(1, "decline", "other", MARKER, WINDOW, MARKER,
                new RespondRequest.Cost("1.00", "EUR")).toString()).doesNotContain(MARKER)
                .contains("decision=decline");
        assertThat(new ManualConfirmationRequest(WINDOW, "phone", MARKER).toString()).doesNotContain(MARKER);
        assertThat(new CancellationRequest("other", MARKER).toString()).doesNotContain(MARKER);
        assertThat(new CompletionRequest(MARKER, "2026-11-26T10:30:00Z", MARKER).toString()).doesNotContain(MARKER);
        assertThat(new ReworkRequest(MARKER).toString()).doesNotContain(MARKER);
    }
}
