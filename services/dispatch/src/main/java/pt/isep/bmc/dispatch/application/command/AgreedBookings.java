package pt.isep.bmc.dispatch.application.command;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import pt.isep.bmc.dispatch.application.port.out.BookingRepository;
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.application.port.out.EventOutbox;
import pt.isep.bmc.dispatch.application.port.out.IdGenerator;
import pt.isep.bmc.dispatch.application.port.out.OutboxEvent;
import pt.isep.bmc.dispatch.domain.booking.Booking;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.BookingState;
import pt.isep.bmc.dispatch.domain.event.BookingStateChanged;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionState;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;

/**
 * The booking request that agreement starts (US-08: "only after approval and
 * acceptance of the same version"), saved in the same transaction as the
 * agreement and before any call: the worker sends it.
 */
final class AgreedBookings {

    private AgreedBookings() {
    }

    /** Requests the agreed window when the intervention has just become {@code scheduling_pending}. */
    static void requestIfAgreed(
            Intervention agreed, TimeWindow window, ProjectedIssue issue, ContractorCatalogue catalogue,
            BookingRepository bookings, EventOutbox outbox, IdGenerator ids, String correlationId, Instant now) {
        if (agreed.state() != InterventionState.SCHEDULING_PENDING) {
            return;
        }
        Booking booking = Booking.request(new BookingId(ids.newId()), agreed.id(),
                catalogue.findCalendarRef(agreed.contractorId()), agreed.buildingId().toString(), summary(issue),
                window, now);
        bookings.add(booking);
        if (booking.state() == BookingState.FAILED) {
            outbox.append(new OutboxEvent(ids.newId(), new BookingStateChanged(agreed.id().value(), agreed.version(),
                    agreed.buildingId(), now, agreed.issueId(), booking.state(), agreed.state()), correlationId));
        }
    }

    /** "Plumbing intervention": the category only, never report text. */
    static String summary(ProjectedIssue issue) {
        Optional<String> category = issue.category().filter(c -> !c.isBlank());
        return category.map(c -> c.substring(0, 1).toUpperCase(Locale.ROOT) + c.substring(1).replace('_', ' ')
                + " intervention").orElse("Intervention");
    }
}
