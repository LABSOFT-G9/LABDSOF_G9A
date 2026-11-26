package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.contractor.ContractorEligibility;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.projection.AdministratorAccess;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.InvalidTransitionException;
import pt.isep.bmc.dispatch.domain.shared.PlainText;
import pt.isep.bmc.dispatch.domain.shared.UserId;
import pt.isep.bmc.dispatch.domain.shared.ValidationException;

/**
 * Drafting an intervention request (US-06, technical design 3.2 and 4.3).
 * Selecting a contractor sends nothing and confirms neither acceptance nor a
 * booking: the draft waits in {@code awaiting_approval} for US-07.
 *
 * <p>The checks run in a fixed order, and each answers before the next one
 * runs, so a caller learns nothing it may not know:
 * <ol>
 *   <li>the issue is in the projection and the caller administers its
 *       building ({@code 404} otherwise, also for residents and contractors);</li>
 *   <li>the request's fields ({@code 400}, every invalid field at once);</li>
 *   <li>the issue is dispatchable and has no other non-terminal
 *       intervention ({@code 409 INVALID_TRANSITION});</li>
 *   <li>the contractor exists and is eligible now ({@code 400}, field
 *       {@code contractorId}, reason {@code not_eligible}: an unknown and an
 *       ineligible contractor answer the same).</li>
 * </ol>
 */
public final class InterventionDrafting {

    public static final int SCOPE_MIN = 10;
    public static final int SCOPE_MAX = 2_000;
    public static final int TERMS_MIN = 1;
    public static final int TERMS_MAX = 1_000;

    static final String FIELD_CONTRACTOR = "contractorId";
    static final String FIELD_SCOPE = "scopeExtract";
    static final String FIELD_WINDOW_START = "window.start";
    static final String FIELD_WINDOW_END = "window.end";
    static final String FIELD_TERMS = "terms";

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private InterventionDrafting() {
    }

    /** Step 1: the caller administers the issue's building. */
    public static void authorise(AdministratorAccess access, ProjectedIssue issue) {
        access.requireAdministers(issue.buildingId());
    }

    /**
     * Step 2: the request's fields, all reported together. Times are RFC 3339
     * with an offset and are kept in UTC at millisecond precision.
     *
     * @throws ValidationException listing every invalid field
     */
    public static DraftTerms validate(
            String contractorId, String scopeExtract, String windowStart, String windowEnd, String terms, Instant now) {
        List<FieldViolation> violations = new ArrayList<>();
        Optional<ContractorId> contractor = contractorId(contractorId, violations);
        PlainText.violation(scopeExtract, SCOPE_MIN, SCOPE_MAX)
                .ifPresent(reason -> violations.add(new FieldViolation(FIELD_SCOPE, reason)));
        PlainText.violation(terms, TERMS_MIN, TERMS_MAX)
                .ifPresent(reason -> violations.add(new FieldViolation(FIELD_TERMS, reason)));
        Optional<Instant> start = instant(windowStart, FIELD_WINDOW_START, violations);
        Optional<Instant> end = instant(windowEnd, FIELD_WINDOW_END, violations);
        if (start.isPresent() && !start.get().isAfter(now)) {
            violations.add(new FieldViolation(FIELD_WINDOW_START, "not_in_future"));
        }
        if (start.isPresent() && end.isPresent() && !end.get().isAfter(start.get())) {
            violations.add(new FieldViolation(FIELD_WINDOW_END, "not_after_start"));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return new DraftTerms(contractor.orElseThrow(), scopeExtract.strip(), new TimeWindow(start.get(), end.get()),
                terms.strip());
    }

    /**
     * Steps 3 and 4, then the draft: {@code awaiting_approval}, version 1,
     * with proposal version 1 written by the administrator.
     *
     * @param activeIntervention the issue's non-terminal intervention, if it has one
     * @param contractor         the catalogue entry for {@code terms.contractorId()}, if it exists
     */
    public static DraftOutcome draft(
            AdministratorAccess access,
            ProjectedIssue issue,
            Optional<InterventionId> activeIntervention,
            DraftTerms terms,
            Optional<Contractor> contractor,
            InterventionId newId,
            Instant now) {
        authorise(access, issue);
        requireDispatchable(issue, activeIntervention);
        requireEligible(contractor, issue);
        UserId actor = access.userId();
        Intervention intervention = new Intervention(newId, issue.issueId(), issue.buildingId(), terms.contractorId(),
                InterventionState.AWAITING_APPROVAL, Intervention.INITIAL_VERSION, 1, issue.version(),
                issue.appliedAt(), actor, now, now);
        ProposalVersion proposal = new ProposalVersion(newId, 1, ProposalVersion.AUTHOR_ADMINISTRATOR,
                terms.scopeExtract(), terms.window(), terms.terms(), actor, now);
        return new DraftOutcome(intervention, proposal);
    }

    /**
     * Replaces the contractor of a draft (US-06): the caller administers the
     * intervention's building ({@code 404}); the contractor ID is valid
     * ({@code 400}); the intervention is still {@code awaiting_approval} and
     * unchanged since {@code expectedVersion} ({@code 409}); the issue may still
     * be dispatched ({@code 409}); the new contractor is eligible now
     * ({@code 400 not_eligible}). Saving moves the version, even when the same
     * contractor is saved again: the save re-checked eligibility.
     *
     * @param contractorId the raw {@code contractorId} of the request
     * @param contractor   the catalogue entry for it, if it exists; looked up by the caller
     */
    public static Intervention replaceContractor(
            AdministratorAccess access,
            Intervention intervention,
            ProjectedIssue issue,
            long expectedVersion,
            ContractorId contractorId,
            Optional<Contractor> contractor,
            Instant now) {
        access.requireAdministers(intervention.buildingId());
        Intervention replaced = intervention.replaceContractor(expectedVersion, contractorId, now);
        if (!issue.dispatchable()) {
            throw new InvalidTransitionException(issue.state());
        }
        requireEligible(contractor, issue);
        return replaced;
    }

    /** The contractor ID of a request, required and in canonical UUID form. */
    public static ContractorId parseContractorId(String raw) {
        List<FieldViolation> violations = new ArrayList<>();
        Optional<ContractorId> id = contractorId(raw, violations);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return id.orElseThrow();
    }

    private static void requireDispatchable(ProjectedIssue issue, Optional<InterventionId> activeIntervention) {
        if (!issue.dispatchable()) {
            throw new InvalidTransitionException(issue.state());
        }
        if (activeIntervention.isPresent()) {
            throw InvalidTransitionException.activeIntervention(issue.state(), activeIntervention.get().value());
        }
    }

    private static void requireEligible(Optional<Contractor> contractor, ProjectedIssue issue) {
        if (contractor.flatMap(c -> ContractorEligibility.evaluate(c, issue)).isEmpty()) {
            throw ValidationException.of(FIELD_CONTRACTOR, "not_eligible");
        }
    }

    private static Optional<ContractorId> contractorId(String raw, List<FieldViolation> violations) {
        if (raw == null || raw.isBlank()) {
            violations.add(new FieldViolation(FIELD_CONTRACTOR, "required"));
            return Optional.empty();
        }
        if (!CANONICAL_UUID.matcher(raw).matches()) {
            violations.add(new FieldViolation(FIELD_CONTRACTOR, "invalid_format"));
            return Optional.empty();
        }
        return Optional.of(new ContractorId(UUID.fromString(raw)));
    }

    private static Optional<Instant> instant(String raw, String field, List<FieldViolation> violations) {
        if (raw == null || raw.isBlank()) {
            violations.add(new FieldViolation(field, "required"));
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(raw).toInstant().truncatedTo(ChronoUnit.MILLIS));
        } catch (DateTimeParseException e) {
            violations.add(new FieldViolation(field, "invalid_format"));
            return Optional.empty();
        }
    }
}
