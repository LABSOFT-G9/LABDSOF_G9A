package pt.isep.bmc.issue.application.command;

import java.util.Objects;
import pt.isep.bmc.issue.application.port.out.IdempotencyKey;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.UserId;

/**
 * @param actor         the token's {@code sub}; the only identity used
 * @param text          raw text as received; validated by the domain
 * @param correlationId copied into the outbox events
 */
public record SubmitReportCommand(
        UserId actor,
        IdempotencyKey idempotencyKey,
        BuildingId buildingId,
        AreaId areaId,
        String text,
        String correlationId) {

    public SubmitReportCommand {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "SubmitReportCommand[actor=" + actor + ", buildingId=" + buildingId + ", areaId=" + areaId + ", text=redacted]";
    }
}
