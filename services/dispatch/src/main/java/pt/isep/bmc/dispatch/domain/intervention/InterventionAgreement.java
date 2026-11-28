package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.contractor.ContractorEligibility;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.AccessDeniedException;
import pt.isep.bmc.dispatch.domain.shared.BusinessRuleException;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;

/**
 * Agreeing the terms of an intervention (US-07, technical design 3.2):
 * approval of a proposal version by the administrator, the contractor's
 * answer, and changed terms.
 *
 * <p>Each command runs its checks in a fixed order, and each answers before
 * the next one runs:
 * <ol>
 *   <li>who: an administrator of the intervention's building, or for a
 *       response the assigned contractor after the request was sent
 *       ({@code 404} otherwise);</li>
 *   <li>the request's fields ({@code 400}, every invalid field at once);</li>
 *   <li>the intervention's state ({@code 409 INVALID_TRANSITION}), then its
 *       version ({@code 409 VERSION_CONFLICT});</li>
 *   <li>the named proposal version is the one to act on ({@code 400}
 *       {@code proposalVersion} {@code not_current});</li>
 *   <li>the issue may still be dispatched ({@code 409 INVALID_TRANSITION}
 *       with the issue's state);</li>
 *   <li>for an approval only, the contractor is still eligible and has an
 *       account ({@code 422}).</li>
 * </ol>
 */
public final class InterventionAgreement {

    static final String FIELD_PROPOSAL_VERSION = "proposalVersion";
    static final String FIELD_DECISION = "decision";
    static final String FIELD_REASON = "reasonCode";
    static final String FIELD_NOTE = "note";
    public static final int NOTE_MAX = 500;

    private InterventionAgreement() {
    }

    // Step 1: who

    /**
     * The assigned contractor: the token's {@code sub} is the catalogue
     * contractor's login, and the request was sent to them. Anyone else,
     * including the contractor before anything was sent, gets {@code 404}.
     */
    public static void requireAssignedContractor(UserId actor, Intervention intervention, Optional<Contractor> contractor) {
        boolean assigned = contractor.filter(c -> c.id().equals(intervention.contractorId()))
                .flatMap(Contractor::userId)
                .filter(actor::equals)
                .isPresent();
        if (!assigned || !intervention.contractorContacted()) {
            throw new AccessDeniedException();
        }
    }

    // Step 2: fields

    /** {@code proposalVersion}: required, a positive integer. */
    public static int validateProposalVersion(Integer raw) {
        List<FieldViolation> violations = new ArrayList<>();
        int version = proposalVersion(raw, violations);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return version;
    }

    /** Changed terms by the administrator: the same fields and rules as a draft, without the contractor. */
    public static ProposalTerms validateTerms(
            String scopeExtract, String windowStart, String windowEnd, String terms, Instant now) {
        List<FieldViolation> violations = new ArrayList<>();
        Optional<String> scope = ProposalFields.scope(scopeExtract, violations);
        Optional<String> text = ProposalFields.terms(terms, violations);
        Optional<TimeWindow> window = ProposalFields.window(windowStart, windowEnd, now, violations);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return new ProposalTerms(scope.orElseThrow(), window.orElseThrow(), text.orElseThrow(), Optional.empty());
    }

    /**
     * The contractor's answer. {@code decision} is {@code accept}, {@code
     * decline} or {@code counterpropose}. A decline needs a {@code reasonCode}
     * and may carry a {@code note} (1–500 characters). A counterproposal needs
     * a window and terms and may carry a cost estimate; the scope stays the
     * administrator's. Fields that do not belong to the decision are ignored.
     */
    public static ResponseRequest validateResponse(
            Integer proposalVersion,
            String decision,
            String reasonCode,
            String note,
            String windowStart,
            String windowEnd,
            String terms,
            String costAmount,
            String costCurrency,
            Instant now) {
        List<FieldViolation> violations = new ArrayList<>();
        int version = proposalVersion(proposalVersion, violations);
        Optional<ResponseDecision> parsed = Optional.empty();
        if (decision == null || decision.isBlank()) {
            violations.add(new FieldViolation(FIELD_DECISION, "required"));
        } else {
            parsed = ResponseDecision.fromValue(decision);
            if (parsed.isEmpty()) {
                violations.add(new FieldViolation(FIELD_DECISION, "invalid"));
            }
        }
        Optional<DeclineReason> reason = Optional.empty();
        Optional<String> declineNote = Optional.empty();
        Optional<CounterTerms> counter = Optional.empty();
        if (parsed.equals(Optional.of(ResponseDecision.DECLINE))) {
            if (reasonCode == null || reasonCode.isBlank()) {
                violations.add(new FieldViolation(FIELD_REASON, "required"));
            } else {
                reason = DeclineReason.fromValue(reasonCode);
                if (reason.isEmpty()) {
                    violations.add(new FieldViolation(FIELD_REASON, "invalid"));
                }
            }
            if (note != null) {
                declineNote = ProposalFields.text(note, FIELD_NOTE, 1, NOTE_MAX, violations);
            }
        }
        if (parsed.equals(Optional.of(ResponseDecision.COUNTERPROPOSE))) {
            Optional<String> text = ProposalFields.terms(terms, violations);
            Optional<TimeWindow> window = ProposalFields.window(windowStart, windowEnd, now, violations);
            Optional<Money> cost = ProposalFields.cost(costAmount, costCurrency, violations);
            if (text.isPresent() && window.isPresent()) {
                counter = Optional.of(new CounterTerms(window.get(), text.get(), cost));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return new ResponseRequest(version, parsed.orElseThrow(), reason, declineNote, counter);
    }

    // Steps 3 to 6, then the change

    /**
     * Approves the current proposal version and sends it to the contractor.
     *
     * @param current    the intervention's current proposal version
     * @param contractor the catalogue entry of the intervention's contractor, as it is now
     */
    public static ApprovalOutcome approve(
            AdministratorAccess access,
            Intervention intervention,
            ProjectedIssue issue,
            ProposalVersion current,
            Optional<Contractor> contractor,
            long expectedVersion,
            int proposalVersion,
            Instant now) {
        access.requireAdministers(intervention.buildingId());
        Intervention approved = intervention.approve(expectedVersion, proposalVersion, current, now);
        requireDispatchable(issue);
        Contractor sentTo = contractor.filter(c -> ContractorEligibility.evaluate(c, issue).isPresent())
                .orElseThrow(BusinessRuleException::contractorNotEligible);
        UserId login = sentTo.userId().orElseThrow(BusinessRuleException::contractorHasNoAccount);
        ProposalVersion approval = current.approve(access.userId(), sentTo.id(), approved.version(), now);
        return new ApprovalOutcome(approved, approval, login, sentTo.displayName());
    }

    /**
     * Records the assigned contractor's answer to the approved version.
     *
     * @param answered the approved proposal version, to copy the scope from for a counterproposal
     */
    public static ResponseOutcome respond(
            UserId contractor,
            Intervention intervention,
            ProjectedIssue issue,
            ProposalVersion answered,
            ResponseRequest request,
            long expectedVersion,
            Instant now) {
        Intervention next = switch (request.decision()) {
            case ACCEPT -> intervention.accept(expectedVersion, request.proposalVersion(), now);
            case DECLINE -> intervention.decline(expectedVersion, request.proposalVersion(), now);
            case COUNTERPROPOSE -> intervention.counterpropose(expectedVersion, request.proposalVersion(), now);
        };
        requireDispatchable(issue);
        if (answered.number() != request.proposalVersion()) {
            throw new IllegalArgumentException("the answered proposal is not the named version");
        }
        Optional<ProposalVersion> counterproposal = request.counter().map(counter -> ProposalVersion.proposed(
                intervention.id(), next.currentProposalVersion(), ProposalVersion.AUTHOR_CONTRACTOR,
                answered.scopeExtract(), counter.window(), counter.terms(), counter.costEstimate(), next.version(),
                contractor, now));
        ContractorResponse response = new ContractorResponse(intervention.id(), request.proposalVersion(),
                request.decision(), request.reason(), request.note(),
                counterproposal.map(ProposalVersion::number), next.version(), contractor, now);
        return new ResponseOutcome(next, response, counterproposal);
    }

    /** A new administrator version; an earlier approval no longer counts. */
    public static TermsOutcome changeTerms(
            AdministratorAccess access,
            Intervention intervention,
            ProjectedIssue issue,
            ProposalTerms terms,
            long expectedVersion,
            Instant now) {
        access.requireAdministers(intervention.buildingId());
        Intervention next = intervention.changeTerms(expectedVersion, now);
        requireDispatchable(issue);
        ProposalVersion proposal = ProposalVersion.proposed(intervention.id(), next.currentProposalVersion(),
                ProposalVersion.AUTHOR_ADMINISTRATOR, terms.scopeExtract(), terms.window(), terms.terms(),
                terms.costEstimate(), next.version(), access.userId(), now);
        return new TermsOutcome(next, proposal);
    }

    private static int proposalVersion(Integer raw, List<FieldViolation> violations) {
        if (raw == null) {
            violations.add(new FieldViolation(FIELD_PROPOSAL_VERSION, "required"));
            return 0;
        }
        if (raw < 1) {
            violations.add(new FieldViolation(FIELD_PROPOSAL_VERSION, "invalid"));
        }
        return raw;
    }

    private static void requireDispatchable(ProjectedIssue issue) {
        if (!issue.dispatchable()) {
            throw new InvalidTransitionException(issue.state());
        }
    }

    /** Terms of a proposal version after validation, trimmed. */
    public record ProposalTerms(String scopeExtract, TimeWindow window, String terms, Optional<Money> costEstimate) {

        public ProposalTerms {
            Objects.requireNonNull(scopeExtract, "scopeExtract");
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(terms, "terms");
            Objects.requireNonNull(costEstimate, "costEstimate");
        }

        @Override
        public String toString() {
            return "ProposalTerms[]";
        }
    }

    /** What a counterproposal changes: the window, the terms and an optional cost estimate. */
    public record CounterTerms(TimeWindow window, String terms, Optional<Money> costEstimate) {

        @Override
        public String toString() {
            return "CounterTerms[]";
        }
    }

    /** A contractor's answer after validation. */
    public record ResponseRequest(
            int proposalVersion,
            ResponseDecision decision,
            Optional<DeclineReason> reason,
            Optional<String> note,
            Optional<CounterTerms> counter) {

        @Override
        public String toString() {
            return "ResponseRequest[proposalVersion=" + proposalVersion + ", decision=" + decision + "]";
        }
    }

    /**
     * @param approvedProposal the current version with its approval
     * @param contractorUserId the contractor's login, to address the request
     */
    public record ApprovalOutcome(
            Intervention intervention, ProposalVersion approvedProposal, UserId contractorUserId,
            String contractorDisplayName) {
    }

    /** @param counterproposal the new contractor version, for a counterproposal */
    public record ResponseOutcome(
            Intervention intervention, ContractorResponse response, Optional<ProposalVersion> counterproposal) {
    }

    public record TermsOutcome(Intervention intervention, ProposalVersion proposal) {
    }
}
