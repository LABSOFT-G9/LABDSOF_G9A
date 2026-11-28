package pt.isep.bmc.notification.application.command;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import pt.isep.bmc.notification.application.port.in.ReceiveNotificationRequestUseCase;
import pt.isep.bmc.notification.application.port.out.DeliveryLog;
import pt.isep.bmc.notification.application.port.out.DeliveryResult;
import pt.isep.bmc.notification.application.port.out.IdGenerator;
import pt.isep.bmc.notification.application.port.out.NotificationChannel;
import pt.isep.bmc.notification.application.port.out.TransactionConflictException;
import pt.isep.bmc.notification.application.port.out.TransactionRunner;
import pt.isep.bmc.notification.domain.notification.Notification;
import pt.isep.bmc.notification.domain.notification.NotificationRequest;
import pt.isep.bmc.notification.domain.shared.UserId;

/**
 * The {@code NotificationFacade} of ADR-007: for each recipient of a request,
 * hands one notification to each registered {@link NotificationChannel},
 * claiming every delivery in the {@link DeliveryLog} first so that none
 * happens twice. Producers never know which channels exist.
 *
 * <p>Everything for one request is one transaction: every recipient on every
 * channel, or nothing. The message is acknowledged only after the commit.
 * Two deliveries of one request that run at the same time can deadlock on the
 * delivery log's key; the loser is rolled back and simply runs again, then
 * finds the winner's rows.
 *
 * <p>Only the in-app channel exists in US-10-01. It is always on, so there are
 * no preferences yet; a channel that can be switched off (email) adds that
 * check here, recorded as {@code skipped_by_preference}.
 */
public final class NotificationFacade implements ReceiveNotificationRequestUseCase {

    /** Attempts when concurrent transactions get in the way; each one commits nothing until it wins. */
    static final int MAX_ATTEMPTS = 10;

    private final TransactionRunner transactions;
    private final DeliveryLog deliveries;
    private final List<NotificationChannel> channels;
    private final IdGenerator ids;
    private final Clock clock;

    public NotificationFacade(
            TransactionRunner transactions,
            DeliveryLog deliveries,
            List<NotificationChannel> channels,
            IdGenerator ids,
            Clock clock) {
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("at least one notification channel is required");
        }
        Set<Object> seen = new HashSet<>();
        for (NotificationChannel channel : channels) {
            if (!seen.add(channel.id())) {
                throw new IllegalArgumentException("more than one channel " + channel.id());
            }
        }
        this.transactions = transactions;
        this.deliveries = deliveries;
        this.channels = List.copyOf(channels);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Receipt receive(NotificationRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.inTransaction(() -> notify(request));
            } catch (TransactionConflictException raced) {
                if (attempt == MAX_ATTEMPTS) {
                    throw raced;
                }
            }
        }
    }

    private Receipt notify(NotificationRequest request) {
        Instant now = clock.instant();
        int delivered = 0;
        int duplicates = 0;
        for (UserId recipient : request.recipients()) {
            Notification notification = Notification.forRecipient(ids.newId(), request, recipient, now);
            for (NotificationChannel channel : channels) {
                if (!deliveries.claim(notification, channel.id(), now)) {
                    duplicates++;
                    continue;
                }
                DeliveryResult result = channel.deliver(notification);
                deliveries.record(notification, channel.id(), result, now);
                delivered++;
            }
        }
        return new Receipt(delivered, duplicates);
    }
}
