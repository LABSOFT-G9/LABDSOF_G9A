package pt.isep.bmc.dispatch.adapters.in.rest;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.dispatch.adapters.in.rest.dto.EligibleContractorsResponse;
import pt.isep.bmc.dispatch.application.port.in.ListEligibleContractorsQuery;
import pt.isep.bmc.dispatch.application.query.EligibleContractors;
import pt.isep.bmc.dispatch.domain.contractor.ContractorEligibility;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;

/**
 * The contractors an administrator may select for an issue (US-06, technical
 * design 4.2). Paths are service-relative; the edge serves them under
 * {@code /api/dispatch}.
 */
@RestController
@RequestMapping(path = "/contractors", produces = MediaType.APPLICATION_JSON_VALUE)
class ContractorController {

    private final ListEligibleContractorsQuery eligible;

    ContractorController(ListEligibleContractorsQuery eligible) {
        this.eligible = eligible;
    }

    /**
     * Eligible contractors for the issue, in the documented order, with the
     * criteria used. {@code 404} when the issue is unknown or the caller does
     * not administer its building.
     */
    @GetMapping
    EligibleContractorsResponse list(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(name = "issueId", required = false) String issueId) {
        RequestIds.check(issueId, "issueId").ifPresent(violation -> {
            throw new ValidationException(List.of(violation));
        });
        EligibleContractors result = eligible.list(Actors.of(jwt), new IssueId(RequestIds.parse(issueId).orElseThrow()));
        return new EligibleContractorsResponse(
                result.issueId().toString(),
                new EligibleContractorsResponse.Criteria(
                        result.category().orElse(null),
                        result.issueState(),
                        result.dispatchable(),
                        List.of("active", "specialisation", "coverage:building"),
                        ContractorEligibility.ORDER),
                result.items().stream()
                        .map(item -> new EligibleContractorsResponse.Item(
                                item.contractor().id().toString(),
                                item.contractor().displayName(),
                                item.contractor().synthetic(),
                                item.contractor().specialisations().stream().sorted().toList(),
                                item.matched(),
                                ContractorEligibility.UNKNOWN))
                        .toList());
    }
}
