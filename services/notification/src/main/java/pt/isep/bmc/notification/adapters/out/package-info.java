/**
 * Driven adapters (ADR-009): implementations of the application's output ports.
 *
 * <ul>
 *   <li>{@code channel}: the {@code NotificationChannel} implementations; only
 *       the in-app channel, which writes the inbox, in US-10-01.</li>
 *   <li>{@code persistence}: the delivery log and the inbox reader through
 *       plain SQL, which keeps the idempotency key explicit.</li>
 *   <li>{@code id}: UUIDv7 generation.</li>
 * </ul>
 *
 * <p>Must not depend on {@code adapters.in}.
 */
package pt.isep.bmc.notification.adapters.out;
