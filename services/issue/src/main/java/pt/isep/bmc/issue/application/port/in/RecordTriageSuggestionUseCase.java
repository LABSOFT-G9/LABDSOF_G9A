package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.command.RecordTriageSuggestionCommand;
import pt.isep.bmc.issue.application.command.SuggestionResult;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.domain.shared.ValidationException;

public interface RecordTriageSuggestionUseCase {

    /**
     * Records a triage suggestion that arrived in {@code TriageSuggested}.
     * The same event recorded again has no effect.
     *
     * <p>Not a user action: the caller is the consumer of the service's own
     * queue, so there is no actor and no permission check.
     *
     * @throws NotFoundException   if the issue does not exist; the event can never be applied
     * @throws ValidationException if the suggestion does not match the contract; the event can never be applied
     */
    SuggestionResult record(RecordTriageSuggestionCommand command);
}
