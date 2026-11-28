package pt.isep.bmc.dispatch.adapters.in.rest.dto;

import java.util.List;

/**
 * {@code 200} of {@code GET /contractors?issueId=} (technical design annex A
 * 1.3), with the criteria used so the client can explain the order and an
 * empty list (US-06).
 */
public record EligibleContractorsResponse(String issueId, Criteria criteria, List<Item> items) {

    /**
     * @param category     the issue's decided category, or {@code null} while undecided (then nobody is eligible)
     * @param issueState   the issue's state as Dispatch last saw it
     * @param dispatchable whether a draft can be saved for the issue now
     * @param eligibility  what every listed contractor meets
     * @param order        the fields of the order, first one first
     */
    public record Criteria(
            String category, String issueState, boolean dispatchable, List<String> eligibility, List<String> order) {
    }

    /**
     * @param matched what this contractor meets
     * @param unknown what the catalogue does not know, never invented
     */
    public record Item(
            String contractorId,
            String displayName,
            boolean synthetic,
            List<String> specialisations,
            List<String> matched,
            List<String> unknown) {
    }
}
