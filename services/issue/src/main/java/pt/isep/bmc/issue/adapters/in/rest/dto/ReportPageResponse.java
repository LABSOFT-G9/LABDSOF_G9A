package pt.isep.bmc.issue.adapters.in.rest.dto;

import java.util.List;

public record ReportPageResponse(List<ReportSummaryResponse> items, int page, int size, boolean hasMore) {
}
