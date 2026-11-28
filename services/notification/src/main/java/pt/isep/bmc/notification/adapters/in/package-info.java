/**
 * Driving adapters (ADR-009): the REST controller with its DTO and error
 * mapping, and the handler of {@code NotificationRequested}. They translate
 * transport data into commands and queries of {@code application.port.in};
 * they hold no business rules.
 *
 * <p>Must not depend on {@code adapters.out}.
 */
package pt.isep.bmc.notification.adapters.in;
