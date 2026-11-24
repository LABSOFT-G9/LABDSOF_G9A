/**
 * Driving adapters (ADR-009): REST controllers with their DTOs and error
 * mapping, and scheduled jobs. They translate transport data into commands and
 * queries of {@code application.port.in}; they hold no business rules.
 *
 * <p>Must not depend on {@code adapters.out}.
 */
package pt.isep.bmc.issue.adapters.in;
