package pt.isep.bmc.messaging.inbox;

import pt.isep.bmc.messaging.CloudEvent;

/**
 * Applies one type of event to the service. Each service registers one bean
 * per event type its queue is bound to.
 *
 * <p>{@link #handle} runs inside the transaction that also records the event
 * ID, and only for an event that was not processed before: a repeated delivery
 * never reaches it. Use the same database for the effect, so both commit or
 * neither does. Out-of-order events are the handler's concern: apply by
 * {@link CloudEvent#aggregateVersion()} and ignore stale ones (technical design
 * 3.3).
 */
public interface EventHandler {

    /** The CloudEvents {@code type} this handler applies, for example {@code TriageSuggested}. */
    String eventType();

    /**
     * Applies the event.
     *
     * @throws RejectEventException if the event can never be applied: it goes to the dead-letter queue without retry
     * @throws RuntimeException     on any other failure: the transaction rolls back and the event is delivered again
     */
    void handle(CloudEvent event);
}
