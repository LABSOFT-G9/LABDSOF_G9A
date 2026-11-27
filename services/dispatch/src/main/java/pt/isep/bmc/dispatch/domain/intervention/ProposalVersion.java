package pt.isep.bmc.dispatch.domain.intervention;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * One version of the terms of an intervention (technical design 2.2). US-06
 * creates version 1, written by the administrator; counterproposals and
 * changed terms add versions in US-07. Raw report text is never copied here:
 * the scope extract is written by the administrator.
 *
 * @param number     1 for the first version
 * @param authorRole {@code administrator} or, from US-07, {@code contractor}
 */
public record ProposalVersion(
        InterventionId interventionId,
        int number,
        String authorRole,
        String scopeExtract,
        TimeWindow window,
        String terms,
        UserId createdBy,
        Instant createdAt) {

    public static final String AUTHOR_ADMINISTRATOR = "administrator";

    public ProposalVersion {
        Objects.requireNonNull(interventionId, "interventionId");
        Objects.requireNonNull(authorRole, "authorRole");
        Objects.requireNonNull(scopeExtract, "scopeExtract");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (number < 1) {
            throw new IllegalArgumentException("proposal versions start at 1");
        }
    }

    /** Never prints the administrator's text. */
    @Override
    public String toString() {
        return "ProposalVersion[interventionId=" + interventionId + ", number=" + number + "]";
    }
}
