package pt.isep.bmc.dispatch.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import pt.isep.bmc.dispatch.application.port.out.CalendarPort;
import pt.isep.bmc.dispatch.domain.booking.BookingId;
import pt.isep.bmc.dispatch.domain.booking.CalendarReply;
import pt.isep.bmc.dispatch.domain.intervention.TimeWindow;

/**
 * An in-process calendar for the application tests (ADR-006): each call
 * takes the next scripted reply of its operation; without one, a create
 * books, a lookup finds nothing and a cancellation succeeds.
 */
public class FakeCalendar implements CalendarPort {

    public final Deque<CalendarReply> creates = new ArrayDeque<>();
    public final Deque<CalendarReply> finds = new ArrayDeque<>();
    public final Deque<CalendarReply> cancels = new ArrayDeque<>();
    public final List<String> calls = new ArrayList<>();

    @Override
    public CalendarReply create(BookingCall call) {
        calls.add("create " + call.id() + " " + call.contractorRef() + " " + call.summary());
        CalendarReply reply = creates.poll();
        return reply == null ? new CalendarReply.Booked("SIM-BK-1") : reply;
    }

    @Override
    public CalendarReply find(BookingId id, TimeWindow requested) {
        calls.add("find " + id);
        CalendarReply reply = finds.poll();
        return reply == null ? new CalendarReply.Absent() : reply;
    }

    @Override
    public CalendarReply cancel(String externalRef, BookingId id) {
        calls.add("cancel " + externalRef);
        CalendarReply reply = cancels.poll();
        return reply == null ? new CalendarReply.Cancelled() : reply;
    }

    public long count(String operation) {
        return calls.stream().filter(c -> c.startsWith(operation + " ")).count();
    }
}
