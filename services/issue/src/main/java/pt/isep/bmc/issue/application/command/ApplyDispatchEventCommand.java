package pt.isep.bmc.issue.application.command;

import java.util.Objects;
import pt.isep.bmc.issue.domain.dispatch.DispatchEvent;

/** One Dispatch event, already read from its message, and the correlation ID it came with. */
public record ApplyDispatchEventCommand(DispatchEvent event, String correlationId) {

    public ApplyDispatchEventCommand {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
