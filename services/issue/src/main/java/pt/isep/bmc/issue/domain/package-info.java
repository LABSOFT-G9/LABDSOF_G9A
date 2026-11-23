/**
 * Domain layer (ADR-009): entities, value objects, state rules, domain events
 * and domain errors of the Issue bounded context (technical design section 2.1).
 *
 * <p>Depends on nothing but the Java standard library. No Spring, JPA, Jackson
 * or servlet types; {@code ArchitectureTest} fails the build otherwise.
 */
package pt.isep.bmc.issue.domain;
