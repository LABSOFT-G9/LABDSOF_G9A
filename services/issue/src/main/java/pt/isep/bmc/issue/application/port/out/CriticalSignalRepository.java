package pt.isep.bmc.issue.application.port.out;

import java.util.Set;
import pt.isep.bmc.issue.domain.issue.CriticalSignal;
import pt.isep.bmc.issue.domain.issue.IssueId;

/**
 * The signals of Issue's critical check. Signals are only ever added: there
 * is no way to change or remove one (US-14).
 */
public interface CriticalSignalRepository {

    void add(CriticalSignal signal);

    /** The rule codes already raised on the issue. */
    Set<String> findCodes(IssueId issueId);
}
