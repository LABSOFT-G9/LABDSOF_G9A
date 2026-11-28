package pt.isep.bmc.issue.application.port.in;

import pt.isep.bmc.issue.application.command.ApplyDispatchEventCommand;
import pt.isep.bmc.issue.application.command.DispatchEventResult;

/**
 * US-07-03: apply one Dispatch event from {@code issue.inbox} (technical
 * design 3.3). The RabbitMQ listener of EN-02-02 (#78) acknowledges every
 * result once this returns: a failed event is kept for inspection, not
 * retried. It rejects the message only when this throws, for example when the
 * database is down, and then nothing was stored.
 */
public interface ApplyDispatchEventUseCase {

    DispatchEventResult apply(ApplyDispatchEventCommand command);
}
