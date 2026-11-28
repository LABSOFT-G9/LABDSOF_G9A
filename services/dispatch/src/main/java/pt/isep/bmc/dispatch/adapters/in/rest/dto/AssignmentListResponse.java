package pt.isep.bmc.dispatch.adapters.in.rest.dto;

import java.util.List;

/** {@code 200} of {@code GET /interventions?assigned=me}: requests sent to the caller, newest change first. */
public record AssignmentListResponse(List<AssignmentResponse> items) {
}
