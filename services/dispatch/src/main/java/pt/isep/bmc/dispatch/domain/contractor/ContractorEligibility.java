package pt.isep.bmc.dispatch.domain.contractor;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;

/**
 * Which contractors may be selected for an issue, and in which order (US-06).
 *
 * <ul>
 *   <li><b>Eligible:</b> active, covers the issue's building and has the
 *       issue's decided category as a specialisation. All three must be
 *       known: missing data is never treated as proof, so an issue without
 *       a decided category has no eligible contractor.</li>
 *   <li><b>Order:</b> display name (ignoring case), then the exact display
 *       name, then the contractor ID as the final tie-breaker, so the order
 *       is the same on every call. Price, distance and availability play no
 *       part: they are unknown and reported as such, never invented.</li>
 * </ul>
 */
public final class ContractorEligibility {

    public static final String COVERAGE_BUILDING = "coverage:building";
    public static final String SPECIALISATION_PREFIX = "specialisation:";

    /** What the catalogue does not know, shown as unknown rather than guessed (US-06). */
    public static final List<String> UNKNOWN = List.of("price", "distance", "availability");

    /** The documented order (US-06): display name, then ID. */
    public static final List<String> ORDER = List.of("displayName", "contractorId");

    static final Comparator<Contractor> BY_NAME_THEN_ID = Comparator
            .comparing((Contractor c) -> c.displayName().toLowerCase(Locale.ROOT))
            .thenComparing(Contractor::displayName)
            .thenComparing(c -> c.id().toString());

    private ContractorEligibility() {
    }

    /** The contractor's match for the issue, or empty if it is not eligible. */
    public static Optional<EligibleContractor> evaluate(Contractor contractor, ProjectedIssue issue) {
        Optional<String> category = issue.category();
        if (!contractor.active()
                || category.isEmpty()
                || !contractor.specialisations().contains(category.get())
                || !contractor.coverage().contains(issue.buildingId())) {
            return Optional.empty();
        }
        return Optional.of(new EligibleContractor(
                contractor, List.of(SPECIALISATION_PREFIX + category.get(), COVERAGE_BUILDING)));
    }

    /** The eligible contractors for the issue, in the documented order. */
    public static List<EligibleContractor> rank(Collection<Contractor> catalogue, ProjectedIssue issue) {
        return catalogue.stream()
                .sorted(BY_NAME_THEN_ID)
                .map(contractor -> evaluate(contractor, issue))
                .flatMap(Optional::stream)
                .toList();
    }
}
