package pt.isep.bmc.dispatch.application.port.out;

/**
 * Transactional outbox (ADR-003, technical design 5), as in Issue: events are
 * written in the caller's transaction as {@code pending} rows and published
 * later, so a request never waits for the broker. The publisher is the
 * messaging starter's, wired in EN-02-02 (#78).
 */
public interface EventOutbox {

    void append(OutboxEvent event);
}
