package pt.isep.bmc.issue.application.port.out;

import java.util.Optional;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.TriageDecision;

/** Triage decisions are appended, never changed: a correction is a new decision. */
public interface TriageDecisionRepository {

    void add(TriageDecision decision);

    /** The decision that produced this version of the issue, if any. */
    Optional<TriageDecision> find(IssueId issueId, long resultingVersion);
}
