/**
 * Domain layer (ADR-009): entities, value objects, state rules and domain
 * errors of the Dispatch bounded context (technical design section 2.2):
 * contractors, interventions and the read projections of Issue's data.
 *
 * <p>Depends on nothing but the Java standard library. No Spring, JPA, Jackson
 * or servlet types; {@code ArchitectureTest} fails the build otherwise.
 */
package pt.isep.bmc.dispatch.domain;
