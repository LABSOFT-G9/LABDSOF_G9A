package pt.isep.bmc.issue.adapters.in.rest.dto;

import java.util.List;

/** {@code GET /issues}: one page, newest first. */
public record IssuePageResponse(List<IssueResponse> items, int page, int size, boolean hasMore) {
}
