/**
 * Domain layer (ADR-009): the notification request, the template catalogue
 * and the inbox notification of the Notification bounded context (technical
 * design section 2.3, ADR-007).
 *
 * <p>Depends on nothing but the Java standard library. No Spring, Jackson or
 * servlet types; {@code ArchitectureTest} fails the build otherwise.
 */
package pt.isep.bmc.notification.domain;
