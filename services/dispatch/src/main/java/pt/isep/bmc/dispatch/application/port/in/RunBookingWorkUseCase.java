package pt.isep.bmc.dispatch.application.port.in;

/** Runs the calendar calls that are due (US-08); the scheduler calls it every second. */
public interface RunBookingWorkUseCase {

    /** @return how many bookings were handled */
    int runDue();
}
