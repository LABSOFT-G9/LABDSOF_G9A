package pt.isep.bmc.dispatch.adapters.in.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.isep.bmc.dispatch.application.port.in.RunBookingWorkUseCase;

/** Runs the booking worker every second (US-08). Off with the other jobs when scheduling is off. */
@Component
class BookingWorkerJob {

    private static final Logger log = LoggerFactory.getLogger(BookingWorkerJob.class);

    private final RunBookingWorkUseCase worker;

    BookingWorkerJob(RunBookingWorkUseCase worker) {
        this.worker = worker;
    }

    @Scheduled(initialDelayString = "${bmc.calendar.worker-interval:PT1S}",
            fixedDelayString = "${bmc.calendar.worker-interval:PT1S}")
    void run() {
        try {
            worker.runDue();
        } catch (RuntimeException e) {
            // The next run picks up whatever is still due.
            log.warn("Booking worker run failed: {}", e.getClass().getName());
        }
    }
}
