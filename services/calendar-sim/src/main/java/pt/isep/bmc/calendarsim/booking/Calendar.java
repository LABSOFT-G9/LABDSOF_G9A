package pt.isep.bmc.calendarsim.booking;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The simulated calendar: bookings in memory, at most one per request ID,
 * and the scripted outcomes of the next calls. Every method holds the same
 * lock, so concurrent calls with one request ID still create one booking
 * (NFR-11). Delays happen outside this class, never under the lock.
 */
@Component
public class Calendar {

    private final Map<String, Booking> byRequestId = new LinkedHashMap<>();
    private final Map<Operation, Deque<Outcome>> scripts = new EnumMap<>(Operation.class);
    private final AtomicLong references = new AtomicLong(10_000);
    private final Clock clock;

    public Calendar(Clock clock) {
        this.clock = clock;
        for (Operation operation : Operation.values()) {
            scripts.put(operation, new ArrayDeque<>());
        }
    }

    /** The outcome of the next call of {@code operation}: the first scripted one, or {@link Outcome#OK}. */
    public synchronized Outcome next(Operation operation) {
        Outcome scripted = scripts.get(operation).poll();
        return scripted == null ? Outcome.OK : scripted;
    }

    public synchronized void script(Operation operation, List<Outcome> outcomes) {
        scripts.get(operation).addAll(outcomes);
    }

    public synchronized Map<Operation, List<Outcome>> scripts() {
        Map<Operation, List<Outcome>> copy = new EnumMap<>(Operation.class);
        scripts.forEach((operation, queue) -> copy.put(operation, List.copyOf(queue)));
        return copy;
    }

    public synchronized void clearScripts() {
        scripts.values().forEach(Deque::clear);
    }

    /**
     * Books, or answers the booking already made for this request ID. A slot
     * conflict is another confirmed booking of the same contractor whose
     * window overlaps.
     */
    public synchronized CreateResult create(BookingRequest request) {
        Booking existing = byRequestId.get(request.requestId());
        if (existing != null) {
            return existing.request().equals(request) ? new CreateResult.Replayed(existing) : new CreateResult.KeyReused();
        }
        boolean conflict = byRequestId.values().stream()
                .filter(b -> b.status() == Booking.Status.CONFIRMED)
                .anyMatch(b -> b.request().contractorRef().equals(request.contractorRef())
                        && b.request().window().overlaps(request.window()));
        if (conflict) {
            return new CreateResult.Conflict();
        }
        Booking booking = new Booking(request, "SIM-BK-" + references.incrementAndGet(), Booking.Status.CONFIRMED,
                clock.instant());
        byRequestId.put(request.requestId(), booking);
        return new CreateResult.Created(booking);
    }

    public synchronized Optional<Booking> find(String requestId) {
        return Optional.ofNullable(byRequestId.get(requestId));
    }

    /** Cancels the booking; cancelling it again answers the same. Empty if the reference is unknown. */
    public synchronized Optional<Booking> cancel(String externalRef) {
        Optional<Booking> booking = byExternalRef(externalRef);
        booking.ifPresent(b -> byRequestId.put(b.request().requestId(), b.cancelled()));
        return booking.map(b -> byRequestId.get(b.request().requestId()));
    }

    /** Forgets one booking, as if the provider lost it (the {@code discrepancy} case). */
    public synchronized boolean lose(String externalRef) {
        return byExternalRef(externalRef).map(b -> byRequestId.remove(b.request().requestId()) != null).orElse(false);
    }

    /** Forgets every booking, as a restart does. */
    public synchronized void loseAll() {
        byRequestId.clear();
    }

    public synchronized List<Booking> all() {
        return new ArrayList<>(byRequestId.values());
    }

    private Optional<Booking> byExternalRef(String externalRef) {
        return byRequestId.values().stream().filter(b -> b.externalRef().equals(externalRef)).findFirst();
    }

    /** What {@link #create} did. */
    public sealed interface CreateResult {

        record Created(Booking booking) implements CreateResult {
        }

        /** The same request ID with the same content: the earlier booking, nothing new. */
        record Replayed(Booking booking) implements CreateResult {
        }

        /** The same request ID with other content. */
        record KeyReused() implements CreateResult {
        }

        record Conflict() implements CreateResult {
        }
    }
}
