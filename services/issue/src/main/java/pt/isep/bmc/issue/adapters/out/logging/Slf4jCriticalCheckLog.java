package pt.isep.bmc.issue.adapters.out.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pt.isep.bmc.issue.application.port.out.CriticalCheckLog;
import pt.isep.bmc.issue.domain.issue.IssueId;

/** Writes a failed critical check to the service log: the issue and the exception class, never the report. */
@Component
class Slf4jCriticalCheckLog implements CriticalCheckLog {

    private static final Logger log = LoggerFactory.getLogger(Slf4jCriticalCheckLog.class);

    @Override
    public void checkFailed(IssueId issueId, String failure) {
        log.warn("Critical check did not run for issue {}: {}. Its check stays pending.", issueId, failure);
    }
}
