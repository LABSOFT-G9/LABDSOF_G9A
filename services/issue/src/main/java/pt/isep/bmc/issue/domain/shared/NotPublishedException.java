package pt.isep.bmc.issue.domain.shared;

/**
 * The operation needs a published summary and the issue has none yet
 * ({@code 409 NOT_PUBLISHED}, US-04). Nothing was changed.
 */
public final class NotPublishedException extends DomainException {

    public NotPublishedException() {
        super("NOT_PUBLISHED", "The issue has no published summary yet.");
    }
}
