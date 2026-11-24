package pt.isep.bmc.issue.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The stored outcome of an effectful request, kept for 24 h (technical design
 * 4.6). Holds a hash of the request and a reference to the created resource,
 * never the request body itself.
 *
 * @param requestHash  SHA-256 (hex) of the canonical request
 * @param resourceId   the resource the original request created
 * @param resourceVersion its version in the original response
 */
public record IdempotencyRecord(
        IdempotencyScope scope,
        String requestHash,
        int responseStatus,
        UUID resourceId,
        long resourceVersion,
        Instant createdAt,
        Instant expiresAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
