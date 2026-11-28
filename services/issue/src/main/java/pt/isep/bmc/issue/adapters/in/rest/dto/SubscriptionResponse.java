package pt.isep.bmc.issue.adapters.in.rest.dto;

/** {@code 200} of {@code PUT} and {@code DELETE /issues/{id}/subscription}: the caller's own choice. */
public record SubscriptionResponse(boolean subscribed) {
}
