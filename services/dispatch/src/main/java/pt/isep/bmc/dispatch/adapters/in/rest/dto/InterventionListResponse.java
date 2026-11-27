package pt.isep.bmc.dispatch.adapters.in.rest.dto;

import java.util.List;

/** {@code 200} of {@code GET /interventions?issueId=}: newest first, terminal ones included. */
public record InterventionListResponse(List<InterventionResponse> items) {
}
