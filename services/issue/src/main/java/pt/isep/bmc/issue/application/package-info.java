/**
 * Application layer (ADR-009): use cases split into {@code command} and
 * {@code query} (CQRS as packages only, no separate read store), the ports
 * they need ({@code port.in} driven by adapters, {@code port.out} implemented
 * by adapters) and application errors.
 *
 * <p>Depends only on {@code domain} and the Java standard library; transactions
 * are a port ({@link pt.isep.bmc.issue.application.port.out.TransactionRunner}),
 * not a framework annotation.
 */
package pt.isep.bmc.issue.application;
