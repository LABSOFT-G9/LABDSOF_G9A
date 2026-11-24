package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.command.DecideTriageCommand;
import pt.isep.bmc.issue.application.command.TriageReceipt;

/** {@code POST /issues/{id}/triage} (technical design 3.1 and 4.2, US-02). */
public interface DecideTriageUseCase {

    /**
     * Records an administrator's category and urgency for an issue. The same
     * idempotency key and request return the original receipt with no new
     * effect.
     */
    TriageReceipt decide(DecideTriageCommand command);
}
