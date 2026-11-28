package pt.isep.bmc.dispatch.domain.contractor;

import java.util.List;
import java.util.Objects;

/**
 * A contractor that may be selected for an issue, with why.
 *
 * @param matched the criteria the contractor meets, for example {@code specialisation:plumbing}
 *                and {@code coverage:building}
 */
public record EligibleContractor(Contractor contractor, List<String> matched) {

    public EligibleContractor {
        Objects.requireNonNull(contractor, "contractor");
        matched = List.copyOf(matched);
    }
}
