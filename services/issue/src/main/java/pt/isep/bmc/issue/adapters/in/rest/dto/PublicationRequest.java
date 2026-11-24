package pt.isep.bmc.issue.adapters.in.rest.dto;

/** Body of {@code POST /issues/{id}/publication}: plain text, 10 to 500 characters. */
public record PublicationRequest(String summary) {
}
