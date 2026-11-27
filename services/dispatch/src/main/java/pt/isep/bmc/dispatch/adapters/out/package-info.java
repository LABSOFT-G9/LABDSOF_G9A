/**
 * Driven adapters (ADR-009): implementations of the application's output ports.
 *
 * <ul>
 *   <li>{@code persistence}: aggregates through JPA entities (adapter classes,
 *       never the domain objects) and mappers (ADR-002); idempotency keys,
 *       projections and read models through plain SQL, which keeps their
 *       locking and queries explicit.</li>
 *   <li>{@code id}: UUIDv7 generation.</li>
 * </ul>
 *
 * <p>Must not depend on {@code adapters.in}.
 */
package pt.isep.bmc.dispatch.adapters.out;
