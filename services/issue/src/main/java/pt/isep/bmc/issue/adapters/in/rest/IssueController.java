package pt.isep.bmc.issue.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.issue.adapters.in.rest.dto.IssueHistoryResponse;
import pt.isep.bmc.issue.adapters.in.rest.dto.IssuePageResponse;
import pt.isep.bmc.issue.adapters.in.rest.dto.IssueResponse;
import pt.isep.bmc.issue.adapters.in.rest.dto.TriageReceiptResponse;
import pt.isep.bmc.issue.adapters.in.rest.dto.TriageRequest;
import pt.isep.bmc.issue.application.command.DecideTriageCommand;
import pt.isep.bmc.issue.application.command.TriageReceipt;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.idempotency.IdempotencyKey;
import pt.isep.bmc.issue.application.port.in.DecideTriageUseCase;
import pt.isep.bmc.issue.application.port.in.GetIssueHistoryQuery;
import pt.isep.bmc.issue.application.port.in.GetIssueQuery;
import pt.isep.bmc.issue.application.port.in.ListIssuesQuery;
import pt.isep.bmc.issue.application.query.IssueHistoryItem;
import pt.isep.bmc.issue.application.query.IssuePage;
import pt.isep.bmc.issue.application.query.IssueView;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.IssueCategory;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueUrgency;
import pt.isep.bmc.issue.domain.issue.TriageDecision;
import pt.isep.bmc.issue.domain.issue.TriageReason;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ValidationException;

/**
 * Issues, manual triage and history (technical design 3.1 and 4.2, US-02).
 * Paths are service-relative; the edge serves them under {@code /api/issue}.
 * There is no endpoint that sets an issue's state directly: the state follows
 * from triage here and, later, from Dispatch's events.
 */
@RestController
@RequestMapping(path = "/issues", produces = MediaType.APPLICATION_JSON_VALUE)
class IssueController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final DecideTriageUseCase decideTriage;
    private final GetIssueQuery getIssue;
    private final ListIssuesQuery listIssues;
    private final GetIssueHistoryQuery getHistory;

    IssueController(
            DecideTriageUseCase decideTriage,
            GetIssueQuery getIssue,
            ListIssuesQuery listIssues,
            GetIssueHistoryQuery getHistory) {
        this.decideTriage = decideTriage;
        this.getIssue = getIssue;
        this.listIssues = listIssues;
        this.getHistory = getHistory;
    }

    /**
     * Issues of the buildings the caller administers, plus the issues the
     * caller reported, newest first. {@code buildingId} narrows the list; it
     * never widens it. Triage reasons are never in a list.
     */
    @GetMapping
    IssuePageResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        List<FieldViolation> violations = new ArrayList<>();
        Optional<BuildingId> building = Optional.empty();
        if (buildingId != null) {
            building = RequestIds.parse(buildingId).map(BuildingId::new);
            if (building.isEmpty()) {
                violations.add(new FieldViolation("buildingId", "invalid_format"));
            }
        }
        int pageNumber = RequestIds.intParam(page, 0, 0, Integer.MAX_VALUE / MAX_PAGE_SIZE, "page", violations);
        int pageSize = RequestIds.intParam(size, DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE, "size", violations);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        IssuePage result = listIssues.list(Actors.of(jwt), building, pageNumber, pageSize);
        return new IssuePageResponse(
                result.items().stream().map(IssueController::response).toList(),
                result.page(),
                result.size(),
                result.hasMore());
    }

    /** The issue as the caller may see it, with its version in the {@code ETag}. */
    @GetMapping("/{issueId}")
    ResponseEntity<IssueResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String issueId) {
        IssueView issue = getIssue.get(Actors.of(jwt), id(issueId));
        return ResponseEntity.ok().eTag(Long.toString(issue.version())).body(response(issue));
    }

    /** Oldest first. Residents get the public entries only, without reasons. */
    @GetMapping("/{issueId}/history")
    IssueHistoryResponse history(@AuthenticationPrincipal Jwt jwt, @PathVariable String issueId) {
        return new IssueHistoryResponse(getHistory.history(Actors.of(jwt), id(issueId)).stream()
                .map(IssueController::entry)
                .toList());
    }

    /**
     * An administrator's category and urgency decision. {@code If-Match} is
     * the version the decision was based on; {@code 200} carries the new
     * version in the body and the {@code ETag}. The same
     * {@code Idempotency-Key} replays the same result.
     */
    @PostMapping(path = "/{issueId}/triage", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TriageReceiptResponse> triage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String issueId,
            @RequestHeader(name = EntityVersions.FIELD, required = false) String ifMatch,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) TriageRequest body,
            HttpServletRequest request) {
        TriageRequest input = body == null ? new TriageRequest(null, null, null) : body;
        IssueId id = id(issueId);
        DecideTriageCommand command = new DecideTriageCommand(
                Actors.of(jwt),
                new IdempotencyKey(idempotencyKey),
                id,
                EntityVersions.parseIfMatch(ifMatch),
                input.category(),
                input.urgency(),
                input.reason(),
                CorrelationIdFilter.of(request));
        TriageReceipt receipt = decideTriage.decide(command);
        return ResponseEntity.ok()
                .eTag(Long.toString(receipt.version()))
                .body(new TriageReceiptResponse(
                        receipt.issueId().toString(),
                        receipt.state().value(),
                        receipt.version(),
                        receipt.category().value(),
                        receipt.urgency().value(),
                        TriageDecision.SOURCE_HUMAN));
    }

    /** An ID that is not a UUID cannot name an issue: not found, like any unknown issue. */
    private static IssueId id(String raw) {
        return new IssueId(RequestIds.parse(raw).orElseThrow(NotFoundException::new));
    }

    private static IssueResponse response(IssueView issue) {
        IssueResponse.Triage triage = issue.triage()
                .map(t -> new IssueResponse.Triage(
                        t.category().value(),
                        t.urgency().value(),
                        t.reason().map(TriageReason::value).orElse(null),
                        DateTimeFormatter.ISO_INSTANT.format(t.decidedAt()),
                        TriageDecision.SOURCE_HUMAN))
                .orElse(null);
        return new IssueResponse(
                issue.issueId().toString(),
                issue.buildingId().toString(),
                issue.buildingName(),
                issue.areaLabel(),
                issue.state().value(),
                issue.version(),
                DateTimeFormatter.ISO_INSTANT.format(issue.createdAt()),
                triage,
                null);
    }

    private static IssueHistoryResponse.Entry entry(IssueHistoryItem item) {
        return new IssueHistoryResponse.Entry(
                item.sequence(),
                item.type(),
                item.visibility().value(),
                item.actorRole(),
                DateTimeFormatter.ISO_INSTANT.format(item.occurredAt()),
                item.category().map(IssueCategory::value).orElse(null),
                item.urgency().map(IssueUrgency::value).orElse(null),
                item.reason().map(TriageReason::value).orElse(null));
    }
}
