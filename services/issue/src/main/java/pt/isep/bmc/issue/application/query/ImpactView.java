package pt.isep.bmc.issue.application.query;

/**
 * How many residents confirmed that a shared issue affects them. A count
 * only, never who (access matrix).
 *
 * @param mine whether the caller is one of them
 */
public record ImpactView(long count, boolean mine) {
}
