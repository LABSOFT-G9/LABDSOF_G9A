package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;

/**
 * Applies Issue's {@code IssueStateChanged} to Dispatch's issue projection
 * (technical design 4.3), by version. Called by the event consumer; until
 * the broker is wired (EN-02-02), by tests with synthetic events.
 */
public interface ApplyIssueStateChangedUseCase {

    /** @return whether the event changed the projection; an older or repeated version changes nothing */
    boolean apply(ProjectedIssue issue);
}
