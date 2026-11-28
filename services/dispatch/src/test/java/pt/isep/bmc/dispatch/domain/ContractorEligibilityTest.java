package pt.isep.bmc.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.isep.bmc.dispatch.domain.DomainFixtures.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.contractor.ContractorEligibility;
import pt.isep.bmc.dispatch.domain.contractor.EligibleContractor;

/** US-06: who is eligible, in which order, and that missing data is never proof. */
class ContractorEligibilityTest {

    @Test
    void anActiveContractorWithTheCategoryCoveringTheBuildingIsEligibleWithTheCriteriaItMeets() {
        var match = ContractorEligibility.evaluate(contractor(1, "Plumbing Co.", "plumbing", true, BUILDING_A),
                dispatchablePlumbing());
        assertThat(match).map(EligibleContractor::matched)
                .contains(List.of("specialisation:plumbing", "coverage:building"));
    }

    @Test
    void inactiveOtherTradeOrOtherBuildingIsNotEligible() {
        assertThat(ContractorEligibility.evaluate(contractor(1, "A", "plumbing", false, BUILDING_A),
                dispatchablePlumbing())).isEmpty();
        assertThat(ContractorEligibility.evaluate(contractor(2, "B", "electrical", true, BUILDING_A),
                dispatchablePlumbing())).isEmpty();
        assertThat(ContractorEligibility.evaluate(contractor(3, "C", "plumbing", true, BUILDING_B),
                dispatchablePlumbing())).isEmpty();
    }

    @Test
    void missingDataIsNotProofOfEligibility() {
        Contractor noCoverage = new Contractor(contractorId(1), Optional.empty(), "No coverage", Set.of("plumbing"),
                Set.of(), true, true);
        Contractor noSpecialisation = new Contractor(contractorId(2), Optional.empty(), "No trade", Set.of(),
                Set.of(BUILDING_A), true, true);
        assertThat(ContractorEligibility.evaluate(noCoverage, dispatchablePlumbing())).isEmpty();
        assertThat(ContractorEligibility.evaluate(noSpecialisation, dispatchablePlumbing())).isEmpty();
        assertThat(ContractorEligibility.evaluate(contractor(3, "Plumber", "plumbing", true, BUILDING_A),
                issue("received", false, null)))
                .as("an issue without a decided category has nobody eligible").isEmpty();
    }

    @Test
    void orderIsByDisplayNameIgnoringCaseThenByIdAndTheSameOnEveryCall() {
        List<Contractor> catalogue = List.of(
                contractor(5, "beta Plumbing", "plumbing", true, BUILDING_A),
                contractor(4, "Alpha Plumbing", "plumbing", true, BUILDING_A),
                contractor(3, "Alpha Plumbing", "plumbing", true, BUILDING_A),
                contractor(2, "Zeta Electrical", "electrical", true, BUILDING_A),
                contractor(1, "Aardvark Plumbing", "plumbing", false, BUILDING_A));

        List<String> ranked = ContractorEligibility.rank(catalogue, dispatchablePlumbing()).stream()
                .map(e -> e.contractor().id().toString())
                .toList();

        assertThat(ranked).containsExactly(
                contractorId(3).toString(), contractorId(4).toString(), contractorId(5).toString());
        assertThat(ContractorEligibility.rank(catalogue.reversed(), dispatchablePlumbing()).stream()
                .map(e -> e.contractor().id().toString()).toList()).isEqualTo(ranked);
    }

    @Test
    void priceDistanceAndAvailabilityAreReportedAsUnknown() {
        assertThat(ContractorEligibility.UNKNOWN).containsExactly("price", "distance", "availability");
        assertThat(ContractorEligibility.ORDER).containsExactly("displayName", "contractorId");
    }
}
