package pt.isep.bmc.issue.application.command;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import pt.isep.bmc.issue.application.port.in.AnnounceMembershipsUseCase;
import pt.isep.bmc.issue.application.port.out.EventOutbox;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.application.port.out.OutboxEvent;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.event.DomainEvent;
import pt.isep.bmc.issue.domain.membership.Membership;

/**
 * Announces each membership's current state once per version. The event ID is
 * derived from the membership and its version, so running again writes
 * nothing new, and a receiver that sees the event twice drops the duplicate.
 * All events of one run are written in one transaction.
 *
 * <p>This is not the operator's rebuild of technical design 4.3 step 9, which
 * republishes everything on purpose; that job comes with the outbox publisher
 * (EN-02-02).
 */
public final class MembershipAnnouncementService implements AnnounceMembershipsUseCase {

    /** Correlation ID of every announcement; there is no request behind them. */
    public static final String CORRELATION_ID = "membership-announcement";

    private final TransactionRunner transactions;
    private final MembershipRepository memberships;
    private final EventOutbox outbox;
    private final Clock clock;

    public MembershipAnnouncementService(
            TransactionRunner transactions, MembershipRepository memberships, EventOutbox outbox, Clock clock) {
        this.transactions = transactions;
        this.memberships = memberships;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public int announce() {
        Instant now = clock.instant();
        return transactions.inTransaction(() -> {
            int written = 0;
            for (Membership membership : memberships.findAll()) {
                DomainEvent event = membership.currentStateEvent(now);
                if (outbox.appendIfAbsent(new OutboxEvent(eventId(event), event, CORRELATION_ID))) {
                    written++;
                }
            }
            return written;
        });
    }

    /** The same ID for the same membership, version and event type, on every run. */
    static UUID eventId(DomainEvent event) {
        String name = "issue/" + event.type() + "/" + event.aggregateId() + "/" + event.aggregateVersion();
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
