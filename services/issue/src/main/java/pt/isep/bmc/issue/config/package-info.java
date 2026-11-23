/**
 * Composition root (ADR-009): wires use cases (plain classes) to their port
 * adapters with constructor injection, and holds framework configuration
 * (security, Flyway, scheduling). The only package allowed to see every layer.
 */
package pt.isep.bmc.issue.config;
