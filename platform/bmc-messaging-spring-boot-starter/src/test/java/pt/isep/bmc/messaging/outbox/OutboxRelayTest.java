package pt.isep.bmc.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OutboxRelayTest {

    private static final Duration POLL = Duration.ofSeconds(1);

    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final OutboxRelay relay = new OutboxRelay(publisher, POLL, Duration.ofSeconds(2), Duration.ofMinutes(5));

    @Test
    void waitsThePollIntervalWhenTheOutboxIsDrained() {
        when(publisher.publishPending()).thenReturn(new OutboxPublisher.Pass(3, true, false));

        assertThat(relay.pass()).isEqualTo(POLL);
    }

    @Test
    void goesOnAtOnceAfterAFullBatch() {
        when(publisher.publishPending()).thenReturn(new OutboxPublisher.Pass(50, true, true));

        assertThat(relay.pass()).isEqualTo(Duration.ZERO);
    }

    @Test
    void backsOffLongerAfterEachFailureAndStartsAgainAfterASuccess() {
        when(publisher.publishPending())
                .thenReturn(new OutboxPublisher.Pass(0, false, false))
                .thenReturn(new OutboxPublisher.Pass(1, false, false))
                .thenReturn(new OutboxPublisher.Pass(0, false, false))
                .thenReturn(new OutboxPublisher.Pass(2, true, false))
                .thenReturn(new OutboxPublisher.Pass(0, false, false));

        assertThat(relay.pass()).isEqualTo(Duration.ofSeconds(2));
        assertThat(relay.pass()).isEqualTo(Duration.ofSeconds(4));
        assertThat(relay.pass()).isEqualTo(Duration.ofSeconds(8));
        assertThat(relay.pass()).isEqualTo(POLL);
        assertThat(relay.pass()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aDatabaseOutageDoesNotStopTheLoop() {
        when(publisher.publishPending())
                .thenThrow(new DataAccessResourceFailureException("down"))
                .thenReturn(new OutboxPublisher.Pass(0, true, false));

        assertThat(relay.pass()).isEqualTo(Duration.ofSeconds(2));
        assertThat(relay.pass()).isEqualTo(POLL);
    }
}
