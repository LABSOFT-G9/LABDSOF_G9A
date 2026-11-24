package pt.isep.bmc.issue.application.port.out;

/**
 * Transactional outbox (ADR-003, technical design 5): events are written in
 * the caller's transaction as {@code pending} rows and published later, so a
 * request never waits for the broker. The publisher is part of EN-02-02 (#78).
 */
public interface EventOutbox {

    void append(OutboxEvent event);

    /**
     * Appends the event unless one with the same {@code eventId} was already
     * written (pending or sent).
     *
     * @return true if the event was written
     */
    boolean appendIfAbsent(OutboxEvent event);
}
