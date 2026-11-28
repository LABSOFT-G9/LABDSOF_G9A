package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.application.command.CompletionCommand;
import pt.isep.bmc.dispatch.application.command.ExecutionResult;
import pt.isep.bmc.dispatch.application.command.ReworkCommand;
import pt.isep.bmc.dispatch.application.command.StartCommand;

/** Doing the work (US-09): start, completion and rework. */
public interface ExecutionUseCases {

    /** {@code POST /interventions/{id}/start}, by the assigned contractor. */
    ExecutionResult start(StartCommand command);

    /** {@code POST /interventions/{id}/completion}, by the assigned contractor or an administrator with a reason. */
    ExecutionResult reportCompletion(CompletionCommand command);

    /** {@code POST /interventions/{id}/rework}, by an administrator of the building. */
    ExecutionResult requestRework(ReworkCommand command);
}
