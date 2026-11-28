package pt.isep.bmc.dispatch.domain.contractor;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * A contractor of Dispatch's catalogue (technical design 2.2, US-06). In
 * Sprint B the catalogue is synthetic: every entry is marked as such.
 *
 * @param userId          the Keycloak {@code sub} of the contractor's account, which maps a login to its
 *                        assignments (US-07); empty when the entry has no account
 * @param specialisations issue categories the contractor works on, as Issue's category values
 * @param coverage        buildings the contractor works in
 */
public record Contractor(
        ContractorId id,
        Optional<UserId> userId,
        String displayName,
        Set<String> specialisations,
        Set<BuildingId> coverage,
        boolean active,
        boolean synthetic) {

    public Contractor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        specialisations = Set.copyOf(specialisations);
        coverage = Set.copyOf(coverage);
    }
}
