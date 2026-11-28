package pt.isep.bmc.dispatch.adapters.out.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.application.port.out.CalendarPort.BookingCall;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.CalendarReply;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter against the real calendar simulator ({@code services/calendar-sim}),
 * which must run with {@code SIM_CONTROL_ENABLED=true} and {@code SIM_HANG=PT3S}:
 * every outcome of technical design 4.5 is classified as Dispatch needs it.
 * Runs only when {@code CALENDAR_SIMULATOR_URL} names it, for example:
 *
 * <pre>
 * docker run -d -p 18080:8080 -e SIM_CONTROL_ENABLED=true -e SIM_HANG=PT3S calendar-sim
 * CALENDAR_SIMULATOR_URL=http://localhost:18080 mvn verify -Dit.test=SimulatorCalendarAdapterIT
 * </pre>
 */
class SimulatorCalendarAdapterIT {

    static final String URL = System.getenv("CALENDAR_SIMULATOR_URL");
    static final TimeWindow WINDOW = new TimeWindow(Instant.parse("2099-11-05T09:00:00Z"),
            Instant.parse("2099-11-05T12:00:00Z"));

    final HttpClient http = HttpClient.newHttpClient();
    SimulatorCalendarAdapter calendar;

    @BeforeEach
    void connect() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(), "CALENDAR_SIMULATOR_URL is not set");
        calendar = new SimulatorCalendarAdapter(new CalendarProperties(URL, Duration.ofSeconds(1)),
                JsonMapper.builder().build());
        control("DELETE", "/control/bookings", null);
    }

    void script(String operation, String outcome) throws Exception {
        control("POST", "/control/outcomes",
                "{\"operation\":\"" + operation + "\",\"outcomes\":[{\"type\":\"" + outcome + "\"}]}");
    }

    void control(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(URL + path));
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        assertThat(http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
    }

    static BookingCall call(BookingId id, String contractorRef) {
        return new BookingCall(id, contractorRef, WINDOW, "0199f2a0-0000-7000-8000-00000000b001", "Plumbing intervention");
    }

    @Test
    void aBookingIsConfirmedFoundAgainAndCancelledOnce() {
        BookingId id = new BookingId(UUID.randomUUID());

        CalendarReply created = calendar.create(call(id, "SIM-CTR-001"));

        assertThat(created).isInstanceOf(CalendarReply.Booked.class);
        String ref = ((CalendarReply.Booked) created).externalRef();
        assertThat(calendar.create(call(id, "SIM-CTR-001"))).as("a re-send").isEqualTo(created);
        assertThat(calendar.find(id, WINDOW)).isEqualTo(created);
        assertThat(calendar.find(id, new TimeWindow(WINDOW.start(), WINDOW.end().plusSeconds(3600))))
                .as("held under this request ID, but not for the window asked")
                .isEqualTo(new CalendarReply.Mismatched(ref));
        assertThat(calendar.find(new BookingId(UUID.randomUUID()), WINDOW)).isInstanceOf(CalendarReply.Absent.class);
        assertThat(calendar.cancel(ref, id)).isInstanceOf(CalendarReply.Cancelled.class);
        assertThat(calendar.find(id, WINDOW)).isInstanceOf(CalendarReply.Cancelled.class);
        assertThat(calendar.cancel("SIM-BK-0", id)).isInstanceOf(CalendarReply.UnknownReference.class);
    }

    @Test
    void failuresAreClassifiedAsTheContractSays() throws Exception {
        script("create", "conflict");
        assertThat(calendar.create(call(new BookingId(UUID.randomUUID()), "SIM-CTR-001")))
                .isInstanceOf(CalendarReply.Conflict.class);
        script("create", "unavailable");
        assertThat(calendar.create(call(new BookingId(UUID.randomUUID()), "SIM-CTR-001")))
                .isInstanceOf(CalendarReply.Unavailable.class);
        script("create", "invalid");
        assertThat(calendar.create(call(new BookingId(UUID.randomUUID()), "SIM-CTR-001")))
                .as("a 2xx without a reference").isInstanceOf(CalendarReply.Unknown.class);
        assertThat(calendar.create(call(new BookingId(UUID.randomUUID()), " ")))
                .as("a contract defect").isInstanceOf(CalendarReply.Defect.class);
        script("query", "unavailable");
        assertThat(calendar.find(new BookingId(UUID.randomUUID()), WINDOW)).isInstanceOf(CalendarReply.Unavailable.class);
    }

    @Test
    void aTimeoutAfterCreationIsUnknownAndTheLookupFindsTheBooking() throws Exception {
        BookingId id = new BookingId(UUID.randomUUID());
        script("create", "timeout_after_create");

        assertThat(calendar.create(call(id, "SIM-CTR-001"))).isInstanceOf(CalendarReply.Unknown.class);

        assertThat(calendar.find(id, WINDOW)).isInstanceOf(CalendarReply.Booked.class);
    }

    @Test
    void aLostBookingIsAbsentAndAClosedPortIsUnavailable() throws Exception {
        BookingId id = new BookingId(UUID.randomUUID());
        calendar.create(call(id, "SIM-CTR-001"));
        control("DELETE", "/control/bookings", null);

        assertThat(calendar.find(id, WINDOW)).isInstanceOf(CalendarReply.Absent.class);
        SimulatorCalendarAdapter closed = new SimulatorCalendarAdapter(
                new CalendarProperties("http://localhost:1", Duration.ofSeconds(1)), JsonMapper.builder().build());
        assertThat(closed.create(call(id, "SIM-CTR-001"))).isInstanceOf(CalendarReply.Unavailable.class);
    }
}
