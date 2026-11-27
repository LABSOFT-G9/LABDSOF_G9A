package pt.isep.bmc.issue.adapters.in.rest.dto;

/**
 * {@code 200} of {@code POST /issues/{id}/publication}.
 *
 * @param version the issue's new version; also in the {@code ETag} header
 */
public record PublicationReceiptResponse(String issueId, long version, IssueResponse.Summary summary) {
}
