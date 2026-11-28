package pt.isep.bmc.calendarsim.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The simulated calendar's rules: one booking per request ID, real slot conflicts, idempotent cancellation. */
class CalendarTest {

    static final Instant NINE = Instant.parse("2099-11-05T09:00:00Z");
    final Calendar calendar = new Calendar(Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC));

    static BookingRequest request(String id, String contractor, Instant start, Duration length) {
        return new BookingRequest(id, contractor, new Window(start, start.plus(length)), "B-001", "Plumbing intervention");
    }

    @Test
    void aRequestIdIsBookedOnceAndTheSameRequestReplaysIt() {
        var first = calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(3)));
        var again = calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(3)));

        assertThat(first).isInstanceOf(Calendar.CreateResult.Created.class);
        Booking booking = ((Calendar.CreateResult.Created) first).booking();
        assertThat(booking.externalRef()).startsWith("SIM-BK-");
        assertThat(booking.status()).isEqualTo(Booking.Status.CONFIRMED);
        assertThat(again).isEqualTo(new Calendar.CreateResult.Replayed(booking));
        assertThat(calendar.all()).hasSize(1);
    }

    @Test
    void referencesAreNotHandedOutAgainAfterARestart() {
        Calendar restarted = new Calendar(Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC));

        var before = (Calendar.CreateResult.Created) calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(3)));
        var after = (Calendar.CreateResult.Created) restarted.create(request("r2", "SIM-CTR-1", NINE, Duration.ofHours(3)));

        assertThat(before.booking().externalRef()).matches("SIM-BK-[0-9a-f]{8}-10001");
        assertThat(after.booking().externalRef()).isNotEqualTo(before.booking().externalRef());
        assertThat(restarted.cancel(before.booking().externalRef())).isEmpty();
    }

    @Test
    void aCancelledRequestIdAnswersTheCancelledBookingNotANewOne() {
        var created = (Calendar.CreateResult.Created) calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(3)));
        calendar.cancel(created.booking().externalRef());

        var again = calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(3)));

        assertThat(again).isEqualTo(new Calendar.CreateResult.Replayed(created.booking().cancelled()));
        assertThat(calendar.all()).hasSize(1);
    }

    @Test
    void theSameRequestIdWithOtherContentIsAReusedKey() {
        calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(3)));

        assertThat(calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(2))))
                .isInstanceOf(Calendar.CreateResult.KeyReused.class);
    }

    @Test
    void anOverlappingWindowOfTheSameContractorConflictsButAnAdjacentOneDoesNot() {
        calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(3)));

        assertThat(calendar.create(request("r2", "SIM-CTR-1", NINE.plus(Duration.ofHours(2)), Duration.ofHours(1))))
                .isInstanceOf(Calendar.CreateResult.Conflict.class);
        assertThat(calendar.create(request("r3", "SIM-CTR-1", NINE.plus(Duration.ofHours(3)), Duration.ofHours(1))))
                .isInstanceOf(Calendar.CreateResult.Created.class);
        assertThat(calendar.create(request("r4", "SIM-CTR-2", NINE, Duration.ofHours(3))))
                .as("another contractor").isInstanceOf(Calendar.CreateResult.Created.class);
    }

    @Test
    void cancellingIsIdempotentAndFreesTheSlot() {
        var created = (Calendar.CreateResult.Created) calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(3)));
        String ref = created.booking().externalRef();

        assertThat(calendar.cancel(ref)).hasValueSatisfying(b -> assertThat(b.status()).isEqualTo(Booking.Status.CANCELLED));
        assertThat(calendar.cancel(ref)).hasValueSatisfying(b -> assertThat(b.status()).isEqualTo(Booking.Status.CANCELLED));
        assertThat(calendar.cancel("SIM-BK-0")).isEmpty();
        assertThat(calendar.create(request("r2", "SIM-CTR-1", NINE, Duration.ofHours(3))))
                .isInstanceOf(Calendar.CreateResult.Created.class);
    }

    @Test
    void aLostBookingIsGoneAndScriptsAreTakenInOrder() {
        var created = (Calendar.CreateResult.Created) calendar.create(request("r1", "SIM-CTR-1", NINE, Duration.ofHours(3)));
        assertThat(calendar.lose(created.booking().externalRef())).isTrue();
        assertThat(calendar.find("r1")).isEmpty();
        assertThat(calendar.lose("SIM-BK-0")).isFalse();

        calendar.script(Operation.CREATE, List.of(new Outcome(Outcome.Type.CONFLICT, Duration.ZERO),
                new Outcome(Outcome.Type.UNAVAILABLE, Duration.ZERO)));
        assertThat(calendar.next(Operation.QUERY)).isEqualTo(Outcome.OK);
        assertThat(calendar.next(Operation.CREATE).type()).isEqualTo(Outcome.Type.CONFLICT);
        assertThat(calendar.next(Operation.CREATE).type()).isEqualTo(Outcome.Type.UNAVAILABLE);
        assertThat(calendar.next(Operation.CREATE)).isEqualTo(Outcome.OK);
    }
}
