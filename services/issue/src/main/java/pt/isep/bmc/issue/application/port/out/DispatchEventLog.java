package pt.isep.bmc.issue.application.port.out;

import pt.isep.bmc.issue.domain.dispatch.DispatchEventRecord;

/** Every Dispatch event Issue received and what it did with it (US-07-03). */
public interface DispatchEventLog {

    boolean exists(String eventId);

    /**
     * Stores what happened to an event. Storing the same event ID again changes nothing: two deliveries of a
     * failed event for an unknown issue are not serialised by any issue lock.
     */
    void add(DispatchEventRecord record);
}
