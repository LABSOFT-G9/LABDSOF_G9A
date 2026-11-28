package pt.isep.bmc.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import pt.isep.bmc.notification.application.command.NotificationFacade;
import pt.isep.bmc.notification.application.port.out.DeliveryLog;
import pt.isep.bmc.notification.application.port.out.DeliveryResult;
import pt.isep.bmc.notification.application.port.out.IdGenerator;
import pt.isep.bmc.notification.application.port.out.Inbox;
import pt.isep.bmc.notification.application.port.out.NotificationChannel;
import pt.isep.bmc.notification.application.port.out.TransactionConflictException;
import pt.isep.bmc.notification.application.port.out.TransactionRunner;
import pt.isep.bmc.notification.domain.notification.ChannelId;
import pt.isep.bmc.notification.domain.notification.Notification;
import pt.isep.bmc.notification.domain.shared.UserId;

/**
 * In-memory fakes of the ports, as in Dispatch's application tests, with an
 * in-app channel that writes {@link #inbox}. The transaction runner undoes
 * what a failing transaction wrote, like a real rollback. IDs are increasing,
 * as UUIDv7s are.
 */
public final class InMemoryPorts implements TransactionRunner, DeliveryLog, Inbox, IdGenerator {

    /** The delivery log: state by "request/recipient/channel". */
    public final Map<String, String> deliveries = new HashMap<>();
    /** The in-app inbox: by "request/recipient". */
    public final Map<String, Notification> inbox = new HashMap<>();
    /** The in-memory in-app channel. */
    public final InAppChannel inApp = new InAppChannel();
    /** The next n transactions are rolled back by a concurrent one, as an InnoDB deadlock victim is. */
    public int conflictingTransactions;
    public int transactionsStarted;

    private long nextId = 1;
    private List<Runnable> undo;

    /** A facade over these ports with the given channels; the in-app channel when none are given. */
    public NotificationFacade facade(Clock clock, NotificationChannel... channels) {
        List<NotificationChannel> list = channels.length == 0 ? List.of(inApp) : List.of(channels);
        return new NotificationFacade(this, this, list, this, clock);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        transactionsStarted++;
        if (conflictingTransactions > 0) {
            conflictingTransactions--;
            throw new TransactionConflictException(new IllegalStateException("simulated deadlock"));
        }
        undo = new ArrayList<>();
        try {
            return work.get();
        } catch (RuntimeException e) {
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).run();
            }
            throw e;
        } finally {
            undo = null;
        }
    }

    @Override
    public <T> T readOnly(Supplier<T> work) {
        return work.get();
    }

    @Override
    public boolean claim(Notification notification, ChannelId channel, Instant now) {
        String key = key(notification) + "/" + channel.value();
        if (deliveries.containsKey(key)) {
            return false;
        }
        put(deliveries, key, "pending");
        return true;
    }

    @Override
    public void record(Notification notification, ChannelId channel, DeliveryResult result, Instant now) {
        put(deliveries, key(notification) + "/" + channel.value(), result.state());
    }

    @Override
    public List<Notification> findForRecipient(UserId recipient, Optional<UUID> before, int limit) {
        return inbox.values().stream()
                .filter(n -> n.recipient().equals(recipient))
                .filter(n -> before.map(b -> n.id().compareTo(b) < 0).orElse(true))
                .sorted(Comparator.comparing(Notification::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public long countUnread(UserId recipient) {
        return inbox.values().stream().filter(n -> n.recipient().equals(recipient) && !n.isRead()).count();
    }

    @Override
    public boolean markRead(UserId recipient, UUID notificationId, Instant now) {
        for (Map.Entry<String, Notification> entry : List.copyOf(inbox.entrySet())) {
            Notification n = entry.getValue();
            if (n.id().equals(notificationId) && n.recipient().equals(recipient)) {
                if (!n.isRead()) {
                    put(inbox, entry.getKey(), read(n, now));
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public int markAllRead(UserId recipient, Instant now) {
        int changed = 0;
        for (Map.Entry<String, Notification> entry : List.copyOf(inbox.entrySet())) {
            Notification n = entry.getValue();
            if (n.recipient().equals(recipient) && !n.isRead()) {
                put(inbox, entry.getKey(), read(n, now));
                changed++;
            }
        }
        return changed;
    }

    private static Notification read(Notification n, Instant now) {
        return new Notification(n.id(), n.requestId(), n.recipient(), n.templateKey(), n.params(), n.priority(),
                n.sourceEventId(), n.createdAt(), now);
    }

    @Override
    public UUID newId() {
        return new UUID(0x0199_0000_0000_7000L, nextId++);
    }

    public List<Notification> of(String recipient) {
        return findForRecipient(new UserId(recipient), Optional.empty(), Integer.MAX_VALUE);
    }

    private static String key(Notification notification) {
        return notification.requestId() + "/" + notification.recipient().value();
    }

    private <V> void put(Map<String, V> map, String key, V value) {
        V previous = map.put(key, value);
        if (undo != null) {
            undo.add(() -> {
                if (previous == null) {
                    map.remove(key);
                } else {
                    map.put(key, previous);
                }
            });
        }
    }

    /** Writes the inbox, as the JDBC in-app adapter does. */
    public final class InAppChannel implements NotificationChannel {

        /** When set, the delivery with this number (1-based) fails, as a database that went away would. */
        public int failDelivery;
        private int deliveriesSeen;

        @Override
        public ChannelId id() {
            return ChannelId.IN_APP;
        }

        @Override
        public DeliveryResult deliver(Notification notification) {
            if (++deliveriesSeen == failDelivery) {
                throw new pt.isep.bmc.notification.application.error.StorageUnavailableException(
                        new IllegalStateException("database went away"));
            }
            put(inbox, key(notification), notification);
            return DeliveryResult.SENT;
        }
    }

    /** A clock the tests set by hand. */
    public static final class MutableClock extends Clock {

        private Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void set(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
