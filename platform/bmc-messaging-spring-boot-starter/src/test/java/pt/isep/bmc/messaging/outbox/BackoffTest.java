package pt.isep.bmc.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doublesFromTwoSecondsUpToFiveMinutesAndStaysThere() {
        Backoff backoff = new Backoff(Duration.ofSeconds(2), Duration.ofMinutes(5));

        long[] seconds = new long[10];
        for (int i = 0; i < seconds.length; i++) {
            seconds[i] = backoff.next().toSeconds();
        }

        assertThat(seconds).containsExactly(2, 4, 8, 16, 32, 64, 128, 256, 300, 300);
    }

    @Test
    void startsAgainAfterASuccess() {
        Backoff backoff = new Backoff(Duration.ofSeconds(2), Duration.ofMinutes(5));
        backoff.next();
        backoff.next();

        backoff.reset();

        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void refusesValuesThatCannotGrow() {
        assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
