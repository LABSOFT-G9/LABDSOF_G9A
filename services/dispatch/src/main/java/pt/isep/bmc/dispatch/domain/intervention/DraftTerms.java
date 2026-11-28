package pt.isep.bmc.dispatch.domain.intervention;

import java.util.Objects;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;

/**
 * A draft request after validation: the selected contractor and the terms of
 * proposal version 1, trimmed (US-06).
 */
public record DraftTerms(ContractorId contractorId, String scopeExtract, TimeWindow window, String terms) {

    public DraftTerms {
        Objects.requireNonNull(contractorId, "contractorId");
        Objects.requireNonNull(scopeExtract, "scopeExtract");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(terms, "terms");
    }

    /** Never prints the administrator's text. */
    @Override
    public String toString() {
        return "DraftTerms[contractorId=" + contractorId + "]";
    }
}
