package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** Answer of start, completion and rework. */
public record ExecutionResponse(String interventionId, String state, long version) {
}
