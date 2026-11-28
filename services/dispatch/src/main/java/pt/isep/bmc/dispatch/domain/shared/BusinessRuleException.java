package pt.isep.bmc.dispatch.domain.shared;

/**
 * The request is well formed and allowed in the current state, but a
 * business rule refuses it ({@code 422} with a rule code, technical design
 * 4.1: "semantic rule"). Nothing was changed.
 */
public final class BusinessRuleException extends DomainException {

    private BusinessRuleException(String code, String message) {
        super(code, message);
    }

    /** The contractor has no login, so nobody could answer the request (US-07). */
    public static BusinessRuleException contractorHasNoAccount() {
        return new BusinessRuleException("CONTRACTOR_HAS_NO_ACCOUNT",
                "The contractor has no account on the platform, so the request cannot be sent.");
    }

    /** The contractor is no longer active, or no longer covers the issue's building or category (US-07). */
    public static BusinessRuleException contractorNotEligible() {
        return new BusinessRuleException("CONTRACTOR_NOT_ELIGIBLE",
                "The contractor is no longer eligible for this issue.");
    }

    /** The request was already sent to this contractor, so another one needs a new intervention (US-07). */
    public static BusinessRuleException contractorAlreadyContacted() {
        return new BusinessRuleException("CONTRACTOR_ALREADY_CONTACTED",
                "The request was already sent to this contractor; the contractor cannot be replaced.");
    }
}
