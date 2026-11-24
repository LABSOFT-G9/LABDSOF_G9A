package pt.isep.bmc.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessagingPropertiesTest {

    @Test
    void defaultsAreTheStartingValuesOfTheTechnicalDesign() {
        MessagingProperties properties = new MessagingProperties(null, null, null);

        assertThat(properties.exchange()).isEqualTo("bmc.events");
        assertThat(properties.outbox().enabled()).isFalse();
        assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.outbox().backoffInitial()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.outbox().backoffMax()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.outbox().notPublishedTypes()).isEmpty();
        assertThat(properties.inbox().enabled()).isFalse();
        assertThat(properties.inbox().supportedSchemaVersions()).containsExactly(1);
        assertThat(properties.inbox().processedRetention()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void anEnabledInboxNeedsItsQueue() {
        assertThatThrownBy(() -> new MessagingProperties.Inbox(true, " ", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bmc.messaging.inbox.queue");
    }

    @Test
    void processedEventIdsAreKeptAtLeastSevenDays() {
        assertThatThrownBy(() -> new MessagingProperties.Inbox(
                true, "issue.inbox", null, Set.of(1), null, Duration.ofDays(6), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("7 days");
    }

    @Test
    void theBatchHoldsAtLeastOneRow() {
        assertThatThrownBy(() -> new MessagingProperties.Outbox(true, null, 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
    }
}
