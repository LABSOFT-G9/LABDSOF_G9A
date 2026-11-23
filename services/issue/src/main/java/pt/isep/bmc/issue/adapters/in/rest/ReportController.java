package pt.isep.bmc.issue.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
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
import pt.isep.bmc.issue.adapters.in.rest.dto.ReportPageResponse;
import pt.isep.bmc.issue.adapters.in.rest.dto.ReportReceiptResponse;
import pt.isep.bmc.issue.adapters.in.rest.dto.ReportResponse;
import pt.isep.bmc.issue.adapters.in.rest.dto.ReportSummaryResponse;
import pt.isep.bmc.issue.adapters.in.rest.dto.SubmitReportRequest;
import pt.isep.bmc.issue.application.command.ReportReceipt;
import pt.isep.bmc.issue.application.command.SubmitReportCommand;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.port.in.GetReportQuery;
import pt.isep.bmc.issue.application.port.in.ListReportsQuery;
import pt.isep.bmc.issue.application.port.in.SubmitReportUseCase;
import pt.isep.bmc.issue.application.port.out.IdempotencyKey;
import pt.isep.bmc.issue.application.query.ReportDetails;
import pt.isep.bmc.issue.application.query.ReportPage;
import pt.isep.bmc.issue.application.query.ReportSummary;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.shared.FieldViolation;
import pt.isep.bmc.issue.domain.shared.ValidationException;

/**
 * Reports (technical design 4.2, US-01). Paths are service-relative; the edge
 * serves them under {@code /api/issue}. There is no endpoint to change or
 * delete a report: a submitted report is immutable.
 */
@RestController
@RequestMapping(path = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
class ReportController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final SubmitReportUseCase submitReport;
    private final GetReportQuery getReport;
    private final ListReportsQuery listReports;

    ReportController(SubmitReportUseCase submitReport, GetReportQuery getReport, ListReportsQuery listReports) {
        this.submitReport = submitReport;
        this.getReport = getReport;
        this.listReports = listReports;
    }

    /**
     * {@code 201} with the receipt once the report, its issue and the outbox
     * rows are committed. The same {@code Idempotency-Key} replays the same
     * receipt.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReportReceiptResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = IdempotencyKey.FIELD, required = false) String idempotencyKey,
            @RequestBody(required = false) SubmitReportRequest body,
            HttpServletRequest request) {
        SubmitReportRequest input = body == null ? new SubmitReportRequest(null, null, null) : body;
        List<FieldViolation> violations = new ArrayList<>();
        RequestIds.check(input.buildingId(), "buildingId").ifPresent(violations::add);
        RequestIds.check(input.areaId(), "areaId").ifPresent(violations::add);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        SubmitReportCommand command = new SubmitReportCommand(
                Actors.of(jwt),
                new IdempotencyKey(idempotencyKey),
                new BuildingId(RequestIds.parse(input.buildingId()).orElseThrow()),
                new AreaId(RequestIds.parse(input.areaId()).orElseThrow()),
                input.text(),
                CorrelationIdFilter.of(request));
        ReportReceipt receipt = submitReport.submit(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(Long.toString(receipt.version()))
                .body(new ReportReceiptResponse(
                        receipt.reportId().toString(),
                        receipt.issueId().toString(),
                        receipt.state().value(),
                        receipt.version(),
                        DateTimeFormatter.ISO_INSTANT.format(receipt.submittedAt())));
    }

    @GetMapping("/{reportId}")
    ReportResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String reportId) {
        UserId actor = Actors.of(jwt);
        ReportId id = new ReportId(RequestIds.parse(reportId).orElseThrow(NotFoundException::new));
        ReportDetails details = getReport.get(actor, id);
        return new ReportResponse(
                details.reportId().toString(),
                details.issueId().toString(),
                details.buildingId().toString(),
                details.areaId().toString(),
                details.areaLabel(),
                details.text().value(),
                details.source().value(),
                details.authorUserId().equals(actor),
                DateTimeFormatter.ISO_INSTANT.format(details.submittedAt()),
                new ReportResponse.IssueStatus(details.issueState().value(), details.issueVersion()));
    }

    /**
     * Own reports, plus every report of the buildings the caller administers,
     * newest first. {@code buildingId} narrows the list; it never widens it.
     */
    @GetMapping
    ReportPageResponse list(
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
        int pageNumber = intParam(page, 0, 0, Integer.MAX_VALUE / MAX_PAGE_SIZE, "page", violations);
        int pageSize = intParam(size, DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE, "size", violations);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        UserId actor = Actors.of(jwt);
        ReportPage result = listReports.list(actor, building, pageNumber, pageSize);
        return new ReportPageResponse(
                result.items().stream().map(s -> summary(s, actor)).toList(),
                result.page(),
                result.size(),
                result.hasMore());
    }

    private static ReportSummaryResponse summary(ReportSummary s, UserId actor) {
        return new ReportSummaryResponse(
                s.reportId().toString(),
                s.issueId().toString(),
                s.buildingId().toString(),
                s.areaId().toString(),
                s.areaLabel(),
                s.source().value(),
                s.authorUserId().equals(actor),
                DateTimeFormatter.ISO_INSTANT.format(s.submittedAt()),
                new ReportResponse.IssueStatus(s.issueState().value(), s.issueVersion()));
    }

    private static int intParam(String raw, int fallback, int min, int max, String field, List<FieldViolation> out) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        out.add(new FieldViolation(field, "out_of_range"));
        return fallback;
    }
}
