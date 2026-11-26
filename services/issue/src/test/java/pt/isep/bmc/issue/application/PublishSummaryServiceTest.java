package pt.isep.bmc.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.isep.bmc.issue.application.SharingFixtures.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.issue.application.command.PublicationReceipt;
import pt.isep.bmc.issue.application.command.PublishSummaryCommand;
import pt.isep.bmc.issue.application.command.PublishSummaryService;
import pt.isep.bmc.issue.application.error.IdempotencyKeyReusedException;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.idempotency.IdempotencyKey;
import pt.isep.bmc.issue.application.port.out.OutboxEvent;
import pt.isep.bmc.issue.domain.event.IssueStateChanged;
import pt.isep.bmc.issue.domain.issue.HistoryEntryType;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.shared.AccessDeniedException;
import pt.isep.bmc.issue.domain.shared.ForbiddenException;
import pt.isep.bmc.issue.domain.shared.ValidationException;
import pt.isep.bmc.issue.domain.shared.VersionConflictException;

/** US-04: publishing a summary, at the application layer with in-memory ports. */
class PublishSummaryServiceTest {

    SharingFixtures world;
    InMemoryPorts ports;
    PublishSummaryService service;

    @BeforeEach
    void setUp() {
        world = new SharingFixtures();
        ports = world.ports;
        service = world.publication();
    }

    @Test
    void storesTheSummaryTheNextVersionHistoryAndBothEventsTogether() {
        PublicationReceipt receipt = service.publish(world.publish(ADMIN, "k1", 1, "  " + SUMMARY + "  "));

        assertThat(receipt.issueId()).isEqualTo(world.issue.id());
        assertThat(receipt.version()).isEqualTo(2);
        assertThat(receipt.text().value()).as("trimmed").isEqualTo(SUMMARY);
        assertThat(receipt.publishedAt()).isEqualTo(NOW_MILLIS);
        assertThat(receipt.replayed()).isFalse();

        assertThat(world.stored().version()).isEqualTo(2);
        assertThat(world.stored().isPublished()).isTrue();
        assertThat(ports.summaries).singleElement().satisfies(summary -> {
            assertThat(summary.publishedBy()).isEqualTo(ADMIN);
            assertThat(summary.resultingVersion()).isEqualTo(2);
        });
        assertThat(ports.history).hasSize(2);
        assertThat(ports.history.get(1).type()).isEqualTo(HistoryEntryType.SUMMARY_PUBLISHED);
        assertThat(ports.history.get(1).sequence()).isEqualTo(2);
        assertThat(ports.outbox).extracting(e -> e.event().type())
                .containsExactly("IssueSummaryPublished", "IssueStateChanged");
        assertThat(ports.outbox).extracting(OutboxEvent::correlationId).containsOnly("c-7");
        assertThat(ports.outbox.get(1).event()).isInstanceOfSatisfying(IssueStateChanged.class,
                event -> assertThat(event.aggregateVersion()).isEqualTo(2));
        assertThat(ports.keys).hasSize(1);
    }

    @Test
    void publishingAgainAddsASummaryAndKeepsTheEarlierOne() {
        service.publish(world.publish(ADMIN, "k1", 1, SUMMARY));
        PublicationReceipt changed = service.publish(world.publish(OTHER_ADMIN, "k2", 2, "The door is fixed now, thank you."));

        assertThat(changed.version()).isEqualTo(3);
        assertThat(ports.summaries).extracting(s -> s.resultingVersion()).containsExactly(2L, 3L);
        assertThat(ports.history).hasSize(3);
    }

    @Test
    void theSameKeyAndRequestReplayTheReceiptEvenThoughTheVersionMovedOn() {
        PublicationReceipt first = service.publish(world.publish(ADMIN, "k1", 1, SUMMARY));
        service.publish(world.publish(OTHER_ADMIN, "k2", 2, "The door is fixed now, thank you."));

        PublicationReceipt again = service.publish(world.publish(ADMIN, "k1", 1, SUMMARY));

        assertThat(again.replayed()).isTrue();
        assertThat(again.version()).isEqualTo(first.version());
        assertThat(again.text()).isEqualTo(first.text());
        assertThat(ports.summaries).hasSize(2);
        assertThat(ports.outbox).hasSize(4);
    }

    @Test
    void aRetryIsAnsweredByTheCallersPermissionsNow() {
        service.publish(world.publish(ADMIN, "k1", 1, SUMMARY));
        world.revoke(ADMIN);

        // Not an administrator any more and not a member: the issue does not exist for this caller.
        assertThatThrownBy(() -> service.publish(world.publish(ADMIN, "k1", 1, SUMMARY)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(ports.summaries).hasSize(1);
    }

    @Test
    void theSameKeyWithADifferentRequestIsRefusedAndChangesNothing() {
        service.publish(world.publish(ADMIN, "k1", 1, SUMMARY));

        assertThatThrownBy(() -> service.publish(world.publish(ADMIN, "k1", 1, SUMMARY + " More.")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(world.stored().version()).isEqualTo(2);
        assertThat(ports.summaries).hasSize(1);
    }

    @Test
    void aStaleVersionIsRefusedAndLeavesNoKey() {
        service.publish(world.publish(ADMIN, "k1", 1, SUMMARY));

        assertThatThrownBy(() -> service.publish(world.publish(OTHER_ADMIN, "k2", 1, "The door is fixed now, thank you.")))
                .isInstanceOf(VersionConflictException.class);
        assertThat(ports.summaries).hasSize(1);
        assertThat(ports.keys).as("a rejected request keeps no key").hasSize(1);
    }

    @Test
    void theCheckOrderIsScopeThenRoleThenTheText() {
        // Outside the building: not found, whatever the text or the version.
        assertThatThrownBy(() -> service.publish(world.publish(OUTSIDER, "k1", 9, "x")))
                .isInstanceOf(AccessDeniedException.class);
        // The reporter sees the issue but may not publish, whatever the text.
        assertThatThrownBy(() -> service.publish(world.publish(REPORTER, "k2", 9, "x")))
                .isInstanceOf(ForbiddenException.class);
        // A neighbour does not see an unpublished issue at all.
        assertThatThrownBy(() -> service.publish(world.publish(NEIGHBOUR, "k3", 1, SUMMARY)))
                .isInstanceOf(AccessDeniedException.class);
        // An administrator with an invalid text.
        assertThatThrownBy(() -> service.publish(world.publish(ADMIN, "k4", 1, "short")))
                .isInstanceOf(ValidationException.class);

        assertThat(world.stored().version()).isEqualTo(1);
        assertThat(ports.summaries).isEmpty();
        assertThat(ports.outbox).isEmpty();
        assertThat(ports.keys).isEmpty();
    }

    @Test
    void anUnknownIssueIsNotFoundAndLeavesNoKey() {
        PublishSummaryCommand unknown = new PublishSummaryCommand(ADMIN, new IdempotencyKey("k1"),
                new IssueId(UUID.randomUUID()), 1, SUMMARY, "c-7");

        assertThatThrownBy(() -> service.publish(unknown)).isInstanceOf(NotFoundException.class);
        assertThat(ports.keys).isEmpty();
    }

    @Test
    void aFailureWhileWritingTheEventsRollsEverythingBack() {
        ports.failAppendNumber = 2;

        assertThatThrownBy(() -> service.publish(world.publish(ADMIN, "k1", 1, SUMMARY)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(world.stored().version()).isEqualTo(1);
        assertThat(ports.summaries).isEmpty();
        assertThat(ports.history).hasSize(1);
        assertThat(ports.outbox).isEmpty();
        assertThat(ports.keys).isEmpty();
    }
}
