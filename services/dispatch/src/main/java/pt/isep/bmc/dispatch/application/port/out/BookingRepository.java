package pt.isep.bmc.dispatch.application.port.out;

import java.time.Instant;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.booking.Booking;
import pt.isep.bmc.dispatch.domain.booking.BookingAction;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;

/** Bookings and the log of their calendar calls (technical design 2.2). */
public interface BookingRepository {

    void add(Booking booking);

    Optional<Booking> find(BookingId id);

    /** Locks the row until the transaction ends. */
    Optional<Booking> findForUpdate(BookingId id);

    /** The intervention's latest booking, locked. There is at most one active booking per intervention. */
    Optional<Booking> findLatestForUpdate(InterventionId interventionId);

    void update(Booking booking);

    /**
     * Locks the booking whose next call is due soonest and is due by {@code
     * now}, skipping rows another worker holds.
     */
    Optional<Booking> lockNextDue(Instant now);

    /** One calendar call and its classified outcome, for the booking's history. */
    void logCall(BookingId id, BookingAction action, String outcome, Instant at);
}
